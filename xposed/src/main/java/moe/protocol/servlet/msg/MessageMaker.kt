@file:Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
package moe.protocol.servlet.msg

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.tencent.mobileqq.app.QQAppInterface
import com.tencent.mobileqq.emoticon.QQSysFaceUtil
import com.tencent.mobileqq.pb.ByteStringMicro
import com.tencent.qphone.base.remote.ToServiceMsg
import com.tencent.qqnt.kernel.nativeinterface.*
import kotlinx.io.core.BytePacketBuilder
import kotlinx.io.core.readBytes
import kotlinx.io.core.toByteArray
import kotlinx.io.core.writeFully
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.protocol.servlet.helper.ContactHelper
import moe.protocol.servlet.helper.LocalCacheHelper
import moe.protocol.servlet.helper.LogicException
import moe.protocol.servlet.helper.MusicHelper
import moe.protocol.servlet.helper.ParamsException
import moe.protocol.servlet.helper.IllegalParamsException
import moe.protocol.servlet.ark.ArkAppInfo
import moe.protocol.servlet.ark.ArkMsgSvc
import moe.protocol.servlet.CardSvc
import moe.protocol.servlet.GroupSvc
import moe.protocol.servlet.LbsSvc
import moe.protocol.servlet.transfile.FileTransfer
import moe.protocol.servlet.transfile.Transfer
import moe.protocol.servlet.utils.FileUtils
import moe.protocol.servlet.transfile.PictureResource
import moe.protocol.servlet.transfile.Private
import moe.protocol.servlet.transfile.Troop
import moe.protocol.servlet.transfile.VideoResource
import moe.protocol.servlet.transfile.VoiceResource
import moe.protocol.servlet.transfile.trans
import moe.protocol.servlet.transfile.with
import moe.protocol.servlet.utils.PlatformUtils
import moe.fuqiuluo.utils.AudioUtils
import moe.fuqiuluo.utils.DeflateTools
import moe.fuqiuluo.utils.MediaType
import moe.fuqiuluo.xposed.helper.Level
import moe.fuqiuluo.xposed.helper.LogCenter
import moe.fuqiuluo.xposed.helper.NTServiceFetcher
import moe.fuqiuluo.xposed.helper.msgService
import moe.fuqiuluo.xposed.tools.asBooleanOrNull
import moe.fuqiuluo.xposed.tools.asInt
import moe.fuqiuluo.xposed.tools.asIntOrNull
import moe.fuqiuluo.xposed.tools.asJsonObject
import moe.fuqiuluo.xposed.tools.asLong
import moe.fuqiuluo.xposed.tools.asString
import moe.fuqiuluo.xposed.tools.asStringOrNull
import moe.fuqiuluo.xposed.tools.ifNullOrEmpty
import moe.protocol.servlet.ark.WeatherSvc
import moe.protocol.servlet.helper.ActionMsgException
import mqq.app.MobileQQ
import tencent.im.oidb.cmd0xb77.oidb_cmd0xb77
import tencent.im.oidb.cmd0xdc2.oidb_cmd0xdc2
import tencent.im.oidb.oidb_sso
import java.io.File
import kotlin.math.roundToInt

internal typealias IMaker = suspend (Int, Long, String, JsonObject) -> Result<MsgElement>

internal object MessageMaker {
    private val makerArray = mutableMapOf(
        "text" to MessageMaker::createTextElem,
        "face" to MessageMaker::createFaceElem,
        "pic" to MessageMaker::createImageElem,
        "image" to MessageMaker::createImageElem,
        "voice" to MessageMaker::createRecordElem,
        "record" to MessageMaker::createRecordElem,
        "at" to MessageMaker::createAtElem,
        "video" to MessageMaker::createVideoElem,
        "markdown" to MessageMaker::createMarkdownElem,
        "dice" to MessageMaker::createDiceElem,
        "rps" to MessageMaker::createRpsElem,
        "poke" to MessageMaker::createPokeElem,
        "anonymous" to MessageMaker::createAnonymousElem,
        "share" to MessageMaker::createShareElem,
        "contact" to MessageMaker::createContactElem,
        "location" to MessageMaker::createLocationElem,
        "music" to MessageMaker::createMusicElem,
        "reply" to MessageMaker::createReplyElem,
        "touch" to MessageMaker::createTouchElem,
        "weather" to MessageMaker::createWeatherElem,
        //"multi_msg" to MessageMaker::createLongMsgStruct,
    )

    private suspend fun createTouchElem(
        chatType: Int,
        msgId: Long,
        peerId: String,
        data: JsonObject
    ): Result<MsgElement> {
        data.checkAndThrow("id")
        GroupSvc.poke(peerId, data["id"].asString)
        return Result.failure(ActionMsgException)
    }

    private suspend fun createWeatherElem(
        chatType: Int,
        msgId: Long,
        peerId: String,
        data: JsonObject
    ): Result<MsgElement> {
        var code = data["code"].asIntOrNull

        if (code == null) {
            data.checkAndThrow("city")
            val city = data["city"].asString
            code = WeatherSvc.searchCity(city).onFailure {
                LogCenter.log("无法获取城市天气: $city", Level.ERROR)
            }.getOrNull()?.firstOrNull()?.adcode
        }

        if (code != null) {
            WeatherSvc.fetchWeatherCard(code).onSuccess {
                val element = MsgElement()
                element.elementType = MsgConstant.KELEMTYPEARKSTRUCT
                val share = it["weekStore"]
                    .asJsonObject["share"]
                    .asJsonObject["data"].toString()

                element.arkElement = ArkElement(share, null, MsgConstant.ARKSTRUCTELEMENTSUBTYPEUNKNOWN)

                return Result.success(element)
            }.onFailure {
                LogCenter.log("无法发送天气分享", Level.ERROR)
            }
        }

        return Result.failure(ActionMsgException)
    }

    private suspend fun createReplyElem(
        chatType: Int,
        msgId: Long,
        peerId: String,
        data: JsonObject
    ): Result<MsgElement> {
        data.checkAndThrow("id")
        val element = MsgElement()
        element.elementType = MsgConstant.KELEMTYPEREPLY
        val reply = ReplyElement()
        reply.replayMsgId = data["id"].asString.toLong()
        if(data.containsKey("text")) {
            data.checkAndThrow("qq", "time", "seq")
            reply.replayMsgSeq = data["seq"].asLong
            reply.sourceMsgText = data["text"].asString
            reply.replyMsgTime = data["time"].asLong
            reply.senderUid = data["qq"].asString.toLong()
        }
        element.replyElement = reply
        return Result.success(element)
    }

    private suspend fun createMusicElem(
        chatType: Int,
        msgId: Long,
        peerId: String,
        data: JsonObject
    ): Result<MsgElement> {
        data.checkAndThrow("type")

        when(val type = data["type"].asString) {
            "qq" -> {
                data.checkAndThrow("id")
                val id = data["id"].asString
                if(!MusicHelper.tryShareQQMusicById(chatType, peerId.toLong(), msgId, id)) {
                    LogCenter.log("无法发送QQ音乐分享", Level.ERROR)
                }
            }
            "163" -> {
                data.checkAndThrow("id")
                val id = data["id"].asString
                if(!MusicHelper.tryShare163MusicById(chatType, peerId.toLong(), msgId, id)) {
                    LogCenter.log("无法发送网易云音乐分享", Level.ERROR)
                }
            }
            "custom" -> {
                data.checkAndThrow("url", "audio", "title")
                ArkMsgSvc.tryShareMusic(
                    chatType,
                    peerId.toLong(),
                    msgId,
                    ArkAppInfo.QQMusic,
                    data["title"].asString,
                    data["singer"].asStringOrNull ?: "",
                    data["url"].asString,
                    data["image"].asStringOrNull ?: "",
                    data["audio"].asString
                )
            }
            else -> LogCenter.log("不支持的音乐分享类型: $type", Level.ERROR)
        }

        return Result.failure(ActionMsgException)
    }

    private suspend fun createLocationElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("lat", "lon")

        val lat = data["lat"].asString.toDouble()
        val lon = data["lon"].asString.toDouble()

        LbsSvc.tryShareLocation(chatType, peerId.toLong(), lat, lon).onFailure {
            LogCenter.log("无法发送位置分享", Level.ERROR)
        }

        return Result.failure(ActionMsgException)
    }

    private suspend fun createContactElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("id")
        val type = data["type"].asStringOrNull ?: data["kind"].asStringOrNull
        val id = data["id"].asString
        val elem = MsgElement()

        when (type) {
            "private", "qq" -> {
                val ark = ArkElement(CardSvc.getSharePrivateArkMsg(id.toLong()), null, null)
                elem.arkElement = ark
            }
            "group" -> {
                val ark = ArkElement(GroupSvc.getShareTroopArkMsg(id.toLong()), null, null)
                elem.arkElement = ark
            }
            else -> throw IllegalParamsException("type")
        }

        elem.elementType = MsgConstant.KELEMTYPEARKSTRUCT

        return Result.success(elem)
    }

    private suspend fun createShareElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("title", "url")

        val url = data["url"].asString
        val image = if (data.containsKey("image")) {
            data["image"].asString
        } else {
            val startWithPrefix = url.startsWith("http://") || url.startsWith("https://")
            val endWithPrefix = url.startsWith("/")
            "http://" + url.split("/")[if (startWithPrefix) 2 else 0] + if (!endWithPrefix) {
                "/favicon.ico"
            } else {
                "favicon.ico"
            }
        }
        val title = data["title"].asString
        val content = data["content"].asStringOrNull

        val reqBody = oidb_cmd0xdc2.ReqBody()
        val info = oidb_cmd0xb77.ReqBody()
        info.appid.set(100446242L)
        info.app_type.set(1)
        info.msg_style.set(0)
        info.recv_uin.set(peerId.toLong())
        val clientInfo = oidb_cmd0xb77.ClientInfo()
        clientInfo.platform.set(1)
        info.client_info.set(clientInfo)
        val richMsgBody = oidb_cmd0xb77.RichMsgBody()
        richMsgBody.using_ark.set(true)
        richMsgBody.title.set(title)
        richMsgBody.summary.set(content ?: url)
        richMsgBody.brief.set("[分享] $title")
        richMsgBody.url.set(url)
        richMsgBody.picture_url.set(image)
        info.ext_info.set(oidb_cmd0xb77.ExtInfo().also {
            it.msg_seq.set(msgId)
        })
        info.rich_msg_body.set(richMsgBody)
        reqBody.msg_body.set(info)
        val sendTo = oidb_cmd0xdc2.BatchSendReq()
        when (chatType) {
            MsgConstant.KCHATTYPEGROUP -> sendTo.send_type.set(1)
            MsgConstant.KCHATTYPEC2C -> sendTo.send_type.set(0)
            else -> return createTextElem(
                chatType = chatType,
                msgId = msgId,
                peerId = peerId,
                data = JsonObject(mapOf("text" to JsonPrimitive("[分享] $title\n地址: $url")))
            )
        }
        sendTo.recv_uin.set(peerId.toLong())
        reqBody.batch_send_req.add(sendTo)
        val app = MobileQQ.getMobileQQ().waitAppRuntime() as QQAppInterface
        val to = ToServiceMsg("mobileqq.service", app.currentAccountUin, "OidbSvc.0xdc2_34")
        val oidb = oidb_sso.OIDBSSOPkg()
        oidb.uint32_command.set(0xdc2)
        oidb.uint32_service_type.set(34)
        oidb.bytes_bodybuffer.set(ByteStringMicro.copyFrom(reqBody.toByteArray()))
        oidb.str_client_version.set(PlatformUtils.getClientVersion(MobileQQ.getContext()))
        to.putWupBuffer(oidb.toByteArray())
        to.addAttribute("req_pb_protocol_flag", true)
        app.sendToService(to)
        return Result.failure(ActionMsgException)
    }

    private suspend fun createAnonymousElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        return Result.failure(ActionMsgException)
    }

    private suspend fun createPokeElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("type", "id")
        val elem = MsgElement()
        val face = FaceElement()
        face.faceIndex = 0
        face.faceText = ""
        face.faceType = 5
        face.packId = null
        face.pokeType = data["type"].asInt
        face.spokeSummary = ""
        face.doubleHit = 0
        face.vaspokeId = data["id"].asInt
        face.vaspokeName = ""
        face.vaspokeMinver = ""
        face.pokeStrength = (data["strength"].asIntOrNull ?: data["cnt"].asIntOrNull
                ?: data["count"].asIntOrNull ?: data["time"].asIntOrNull ?: 0).also {
            if(it < 0 || it > 3) throw IllegalParamsException("strength")
        }
        face.msgType = 0
        face.faceBubbleCount = 0
        face.oldVersionStr = "[截一戳]请使用最新版手机QQ体验新功能。"
        face.pokeFlag = 0
        elem.elementType = MsgConstant.KELEMTYPEFACE
        elem.faceElement = face
        return Result.success(elem)
    }

    private suspend fun createFaceElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("id")

        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEFACE
        val face = FaceElement()

        // 4 is market face
        // 5 is vas poke
        face.faceType = 0
        val serverId = data["id"].asInt
        val localId = QQSysFaceUtil.convertToLocal(serverId)
        face.faceIndex = serverId
        face.faceText = QQSysFaceUtil.getFaceDescription(localId)
        face.imageType = 0
        face.packId = "0"
        elem.faceElement = face

        return Result.success(elem)
    }

    private suspend fun createRpsElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEMARKETFACE
        val market = MarketFaceElement(
            6, 1, 11415, 3, 0, 200, 200,
            "[猜拳]", "83C8A293AE65CA140F348120A77448EE", "7de39febcf45e6db",
            null, null, 0, 0, 0, 1, 0,
            null, null, null,
            "", null, null,
            null, null, arrayListOf(MarketFaceSupportSize(200, 200)), null)
        elem.marketFaceElement = market
        return Result.success(elem)
    }

    private suspend fun createDiceElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEMARKETFACE
        val market = MarketFaceElement(
            6, 1, 11464, 3, 0, 200, 200,
            "[骰子]", "4823d3adb15df08014ce5d6796b76ee1", "409e2a69b16918f9",
            null, null, 0, 0, 0, 1, 0,
            null, null, null, // jumpurl
            "", null, null,
            null, null, arrayListOf(MarketFaceSupportSize(200, 200)), null)
        elem.marketFaceElement = market
        return Result.success(elem)
    }

    private suspend fun createMarkdownElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("text")
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEMARKDOWN
        val markdown = MarkdownElement(data["text"].asString)
        elem.markdownElement = markdown
        return Result.success(elem)
    }

    private suspend fun createVideoElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("file")

        val file = data["file"].asString.let {
            val md5 = it.replace(regex = "[{}\\-]".toRegex(), replacement = "").split(".")[0].lowercase()
            var file = if (md5.length == 32) {
                FileUtils.getFile(it)
            } else {
                FileUtils.parseAndSave(it)
            }
            if (!file.exists() && data.containsKey("url")) {
                file = FileUtils.parseAndSave(data["url"].asString)
            }
            return@let file
        }
        if (!file.exists()) {
            throw LogicException("Video(${file.name}) file is not exists, please check your filename.")
        }
        val elem = MsgElement()
        val video = VideoElement()

        video.videoMd5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(file.absolutePath)

        val msgService = NTServiceFetcher.kernelService.msgService!!
        val originalPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
            5, 2, video.videoMd5, file.name, 1, 0, null, "", true
        ))
        val thumbPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
            5, 1, video.videoMd5, file.name, 2, 0, null, "", true
        ))
        if (!QQNTWrapperUtil.CppProxy.fileIsExist(originalPath) || QQNTWrapperUtil.CppProxy.getFileSize(originalPath) != file.length()) {
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, originalPath)
            AudioUtils.obtainVideoCover(file.absolutePath, thumbPath!!)
        }

        Transfer with when (chatType) {
            MsgConstant.KCHATTYPEGROUP -> Troop(peerId)
            MsgConstant.KCHATTYPEC2C -> Private(peerId)
            else -> error("Not supported chatType($chatType) for VideoMsg")
        } trans VideoResource(file, File(thumbPath.toString()))

        video.fileTime = AudioUtils.getVideoTime(file)
        video.fileSize = file.length()
        video.fileName = file.name
        video.fileFormat = FileTransfer.VIDEO_FORMAT_MP4
        video.thumbSize = QQNTWrapperUtil.CppProxy.getFileSize(thumbPath).toInt()
        val options = BitmapFactory.Options()
        BitmapFactory.decodeFile(thumbPath, options)
        video.thumbWidth = options.outWidth
        video.thumbHeight = options.outHeight
        video.thumbMd5 = QQNTWrapperUtil.CppProxy.genFileMd5Hex(thumbPath)
        video.thumbPath = hashMapOf(0 to thumbPath)

        elem.videoElement = video
        elem.elementType = MsgConstant.KELEMTYPEVIDEO

        return Result.success(elem)
    }

    private suspend fun createAtElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        if (chatType != MsgConstant.KCHATTYPEGROUP) {
            return Result.failure(ActionMsgException)
        }
        data.checkAndThrow("qq")

        val elem = MsgElement()
        val qq = data["qq"].asString

        val at = TextElement()
        when(qq) {
            "0", "all" -> {
                at.content = "@全体成员"
                at.atType = MsgConstant.ATTYPEALL
                at.atNtUid = "0"
            }
            "online" -> {
                at.content = "@在线成员"
                at.atType = MsgConstant.ATTYPEONLINE
                at.atNtUid = "0"
            }
            "admin" -> {
                at.content = "@管理员"
                at.atRoleId = 1
                at.atType = MsgConstant.ATTYPEROLE
                at.atNtUid = "0"
            }
            else -> {
                val info = GroupSvc.getTroopMemberInfoByUin(peerId, qq, true).onFailure {
                    LogCenter.log("无法获取群成员信息: $qq", Level.ERROR)
                }.getOrThrow()
                at.content = "@${info.troopnick
                    .ifNullOrEmpty(info.friendnick)
                    .ifNullOrEmpty(qq)}"
                at.atType = MsgConstant.ATTYPEONE
                at.atNtUid = ContactHelper.getUidByUinAsync(qq.toLong())
            }
        }

        elem.textElement = at
        elem.elementType = MsgConstant.KELEMTYPETEXT

        return Result.success(elem)
    }

    private suspend fun createRecordElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("file")

        var file = data["file"].asString.let {
            val md5 = it.replace(regex = "[{}\\-]".toRegex(), replacement = "")
                .replace(" ", "")
                .split(".")[0].lowercase()
            var file = if (md5.length == 32) {
                LocalCacheHelper.getCachePttFile(md5)
            } else {
                FileUtils.parseAndSave(it)
            }
            if (!file.exists() && data.containsKey("url")) {
                file = FileUtils.parseAndSave(data["url"].asString)
            }
            return@let file
        }
        if (!file.exists()) {
            throw LogicException("Voice(${file.name}) file is not exists, please check your filename.")
        }
        val isMagic = data["magic"].asStringOrNull == "1"

        val ptt = PttElement()

        when (AudioUtils.getMediaType(file)) {
            MediaType.Silk -> {
                //LogCenter.log("Silk: $file", Level.DEBUG)

                ptt.formatType = MsgConstant.KPTTFORMATTYPESILK
                ptt.duration = 1
            }
            MediaType.Amr -> {
                //LogCenter.log("Amr: $file", Level.DEBUG)
                ptt.duration = AudioUtils.getDurationSec(file)
                ptt.formatType = MsgConstant.KPTTFORMATTYPEAMR
            }
            MediaType.Pcm -> {
                //LogCenter.log("Pcm To Silk: $file", Level.DEBUG)
                val result = AudioUtils.pcmToSilk(file)
                ptt.duration = (result.second * 0.001).roundToInt()
                file = result.first
                ptt.formatType = MsgConstant.KPTTFORMATTYPESILK
            }
            else -> {
                //LogCenter.log("Audio To SILK: $file", Level.DEBUG)
                val result = AudioUtils.audioToSilk(file)
                ptt.duration = result.first
                file = result.second
                ptt.formatType = MsgConstant.KPTTFORMATTYPESILK
            }
        }
        //val msgService = NTServiceFetcher.kernelService.msgService!!
        //val originalPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
        //    MsgConstant.KELEMTYPEPTT, 0, ptt.md5HexStr, file.name, 1, 0, null, "", true
        //))!!
        //if (!QQNTWrapperUtil.CppProxy.fileIsExist(originalPath) || QQNTWrapperUtil.CppProxy.getFileSize(originalPath) != file.length()) {
        //    QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, originalPath)
        //}

        Transfer with when (chatType) {
            MsgConstant.KCHATTYPEGROUP -> Troop(peerId)
            MsgConstant.KCHATTYPEC2C -> Private(peerId)
            else -> error("Not supported chatType($chatType) for RecordMsg")
        } trans VoiceResource(file)

        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEPTT
        ptt.md5HexStr = QQNTWrapperUtil.CppProxy.genFileMd5Hex(file.absolutePath)

        ptt.fileName = file.name
        ptt.filePath = file.absolutePath
        ptt.fileSize = file.length()

        if (!isMagic) {
            ptt.voiceType = MsgConstant.KPTTVOICETYPESOUNDRECORD
            ptt.voiceChangeType = MsgConstant.KPTTVOICECHANGETYPENONE
        } else {
            ptt.voiceType = MsgConstant.KPTTVOICETYPEVOICECHANGE
            ptt.voiceChangeType = MsgConstant.KPTTVOICECHANGETYPEECHO
        }

        ptt.canConvert2Text = false
        ptt.fileId = 0
        ptt.fileUuid = ""
        ptt.text = ""

        elem.pttElement = ptt

        return Result.success(elem)
    }

    private suspend fun createImageElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("file")
        val isOriginal = data["original"].asBooleanOrNull ?: true
        val isFlash = data["flash"].asBooleanOrNull ?: false
        val file = data["file"].asString.let {
            val md5 = it.replace(regex = "[{}\\-]".toRegex(), replacement = "").split(".")[0].lowercase()
            var tmpPicFile = if (md5.length == 32) {
                FileUtils.getFile(md5)
            } else {
                FileUtils.parseAndSave(it)
            }
            if (!tmpPicFile.exists() && data.containsKey("url")) {
                tmpPicFile = FileUtils.parseAndSave(data["url"].asString)
            }
            return@let tmpPicFile
        }
        if (!file.exists()) {
            throw LogicException("Image(${file.name}) file is not exists, please check your filename.")
        }

        Transfer with when (chatType) {
            MsgConstant.KCHATTYPEGROUP -> Troop(peerId)
            MsgConstant.KCHATTYPEC2C -> Private(peerId)
            else -> error("Not supported chatType($chatType) for PictureMsg")
        } trans PictureResource(file)

        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPEPIC
        val pic = PicElement()
        pic.md5HexStr = QQNTWrapperUtil.CppProxy.genFileMd5Hex(file.absolutePath)

        val msgService = NTServiceFetcher.kernelService.msgService!!
        val originalPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
            2, 0, pic.md5HexStr, file.name, 1, 0, null, "", true
        ))
        if (!QQNTWrapperUtil.CppProxy.fileIsExist(originalPath) || QQNTWrapperUtil.CppProxy.getFileSize(originalPath) != file.length()) {
            val thumbPath = msgService.getRichMediaFilePathForMobileQQSend(RichMediaFilePathInfo(
                2, 0, pic.md5HexStr, file.name, 2, 720, null, "", true
            ))
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, originalPath)
            QQNTWrapperUtil.CppProxy.copyFile(file.absolutePath, thumbPath)
        }

        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        val exifInterface = ExifInterface(file.absolutePath)
        val orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        if (orientation != ExifInterface.ORIENTATION_ROTATE_90 && orientation != ExifInterface.ORIENTATION_ROTATE_270) {
            pic.picWidth = options.outWidth
            pic.picHeight = options.outHeight
        } else {
            pic.picWidth = options.outHeight
            pic.picHeight = options.outWidth
        }
        pic.sourcePath = file.absolutePath
        pic.fileSize = QQNTWrapperUtil.CppProxy.getFileSize(file.absolutePath)
        pic.original = isOriginal
        pic.picType = FileUtils.getPicType(file)
        // GO-CQHTTP扩展参数 支持
        pic.picSubType = data["subType"].asIntOrNull ?: 0
        pic.isFlashPic = isFlash

        elem.picElement = pic

        return Result.success(elem)
    }

    private suspend fun createTextElem(chatType: Int, msgId: Long, peerId: String, data: JsonObject): Result<MsgElement> {
        data.checkAndThrow("text")
        val elem = MsgElement()
        elem.elementType = MsgConstant.KELEMTYPETEXT
        val text = TextElement()
        text.content = data["text"].asString
        elem.textElement = text
        return Result.success(elem)
    }

    private fun JsonObject.checkAndThrow(vararg key: String) {
        key.forEach {
            if (!containsKey(it)) throw ParamsException(it)
        }
    }

    operator fun get(type: String): IMaker? = makerArray[type]
}