package com.chaoqer.server;

import cn.jiguang.common.ClientConfig;
import cn.jiguang.common.resp.APIConnectionException;
import cn.jiguang.common.resp.APIRequestException;
import cn.jpush.api.JPushClient;
import cn.jpush.api.device.DeviceClient;
import cn.jpush.api.push.PushResult;
import cn.jpush.api.push.model.Message;
import cn.jpush.api.push.model.Platform;
import cn.jpush.api.push.model.PushPayload;
import cn.jpush.api.push.model.audience.Audience;
import cn.jpush.api.push.model.notification.AndroidNotification;
import cn.jpush.api.push.model.notification.IosAlert;
import cn.jpush.api.push.model.notification.IosNotification;
import cn.jpush.api.push.model.notification.Notification;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.chaoqer.client.PushAsyncClient;
import com.chaoqer.common.entity.push.*;
import com.chaoqer.common.entity.user.UidDTO;
import com.chaoqer.common.util.CommonUtil;
import com.chaoqer.common.util.RedisKeyGenerator;
import com.chaoqer.common.util.RedisUtil;
import com.chaoqer.entity.JPushProperties;
import com.chaoqer.repository.UserMessageOTS;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import vip.toby.rpc.annotation.RpcServer;
import vip.toby.rpc.annotation.RpcServerMethod;
import vip.toby.rpc.entity.OperateStatus;
import vip.toby.rpc.entity.RpcType;
import vip.toby.rpc.entity.ServerResult;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RpcServer(value = "push", type = RpcType.ASYNC)
public class PushServer {

    private final static Logger logger = LoggerFactory.getLogger(PushServer.class);

    @Autowired
    private Environment env;
    @Autowired
    private UserMessageOTS userMessageOTS;
    @Autowired
    private PushAsyncClient pushAsyncClient;
    @Autowired
    private JPushProperties jPushProperties;
    @Autowired
    private StringRedisTemplate redisTemplate;

    @RpcServerMethod
    public ServerResult updateDevice(@Validated JPushIdDTO jPushIdDTO) {
        String uid = jPushIdDTO.getAuthedUid();
        String jPushId = jPushIdDTO.getJPushId();
        String jPushUpdateApiDisableKey = RedisKeyGenerator.getJPushUpdateApiDisableKey();
        try {
            String jPushUserDeviceIdKey = RedisKeyGenerator.getJPushUserDeviceIdKey(uid);
            if (!jPushId.equals(RedisUtil.getString(redisTemplate, jPushUserDeviceIdKey))) {
                // ?????????????????????
                RedisUtil.delObject(redisTemplate, jPushUserDeviceIdKey);
                // ???????????????uid??????
                String jPushDeviceIdUserKey = RedisKeyGenerator.getJPushDeviceIdUserKey(jPushId);
                String latestUid = RedisUtil.getString(redisTemplate, jPushDeviceIdUserKey);
                if (StringUtils.isNotBlank(latestUid)) {
                    RedisUtil.delObject(redisTemplate, RedisKeyGenerator.getJPushUserDeviceIdKey(latestUid));
                }
                // ?????????????????????updateJPushDevice API
                if (RedisUtil.isKeyExist(redisTemplate, jPushUpdateApiDisableKey)) {
                    logger.warn("??????JPush??????API???????????????, uid: {}, jPushId: {}", uid, jPushId);
                    return ServerResult.build(OperateStatus.SUCCESS);
                }
                // ????????????????????????????????????????????????????????????
                String alias = CommonUtil.getEnvironmentName(env).concat("_").concat(uid);
                DeviceClient deviceClient = new DeviceClient(jPushProperties.getMasterSecret(), jPushProperties.getAppKey());
                // ???????????????????????????????????????
                List<String> deviceIdList = deviceClient.getAliasDeviceList(alias, null).registration_ids;
                if (deviceIdList != null && deviceIdList.size() > 0) {
                    // ????????????????????????????????????????????????
                    deviceClient.deleteAlias(alias, null);
                }
                // ??????????????????????????????
                deviceClient.updateDeviceTagAlias(jPushId, alias, null, null);
                // ??????????????????
                RedisUtil.setObject(redisTemplate, jPushDeviceIdUserKey, uid, 3, TimeUnit.DAYS);
                // ??????????????????????????????
                if (!jPushProperties.getWhiteUidList().contains(uid)) {
                    // ??????3???
                    RedisUtil.setObject(redisTemplate, jPushUserDeviceIdKey, jPushId, 3, TimeUnit.DAYS);
                }
                logger.info("??????JPush????????????, uid: {}, jPushId: {}", uid, jPushId);
            } else {
                // ?????????, ??????
                logger.info("??????JPush???????????????, uid: {}, jPushId: {}", uid, jPushId);
            }
        } catch (APIConnectionException e) {
            logger.error("??????JPush????????????, JPush?????????????????????", e);
            // ?????????API?????????
            RedisUtil.setObject(redisTemplate, jPushUpdateApiDisableKey, true, 60 * 30);
        } catch (Exception e) {
            logger.error("??????JPush????????????, Exception: {}", e.getMessage());
        }
        return ServerResult.build(OperateStatus.SUCCESS);
    }

    @RpcServerMethod
    public ServerResult deleteDevice(@Validated UidDTO uidDTO) {
        String uid = uidDTO.getUid();
        String jPushUpdateApiDisableKey = RedisKeyGenerator.getJPushUpdateApiDisableKey();
        try {
            // ????????????
            RedisUtil.delObject(redisTemplate, RedisKeyGenerator.getJPushUserDeviceIdKey(uid));
            // ?????????????????????updateJPushDevice API
            if (RedisUtil.isKeyExist(redisTemplate, jPushUpdateApiDisableKey)) {
                logger.warn("??????JPush??????API???????????????, uid: {}", uid);
                return ServerResult.build(OperateStatus.SUCCESS);
            }
            DeviceClient deviceClient = new DeviceClient(jPushProperties.getMasterSecret(), jPushProperties.getAppKey());
            // ????????????????????????????????????????????????????????????
            String alias = CommonUtil.getEnvironmentName(env).concat("_").concat(uid);
            // ???????????????????????????????????????
            List<String> deviceIdList = deviceClient.getAliasDeviceList(alias, null).registration_ids;
            if (deviceIdList != null && deviceIdList.size() > 0) {
                // ????????????????????????????????????????????????
                deviceClient.deleteAlias(alias, null);
            }
        } catch (APIConnectionException e) {
            logger.error("??????JPush????????????, JPush?????????????????????", e);
            // ?????????API?????????
            RedisUtil.setObject(redisTemplate, jPushUpdateApiDisableKey, true, 60 * 30);
        } catch (Exception e) {
            logger.error("??????JPush????????????, Exception: {}", e.getMessage());
        }
        return ServerResult.build(OperateStatus.SUCCESS);
    }

    @RpcServerMethod
    public ServerResult pushAlertMessage(@Validated AlertMessageDTO alertMessageDTO) throws InterruptedException {
        String uid = alertMessageDTO.getUid();
        OriginPushType originPushType = OriginPushType.getOriginPushType(alertMessageDTO.getOriginPushType());
        String originUid = "";
        if (originPushType == OriginPushType.INTERACTIVE) {
            originUid = alertMessageDTO.getOriginUid();
            if (StringUtils.isBlank(originUid)) {
                return ServerResult.buildFailureMessage("originUid????????????");
            }
        }
        if (alertMessageDTO.getStore() == 0 && alertMessageDTO.getAlert() == 0) {
            return ServerResult.buildFailureMessage("store???alert???????????????0");
        }
        if (alertMessageDTO.getStore() > 0) {
            // ?????????????????????
            userMessageOTS.asyncSaveUserMessage(uid, originPushType, originUid, MessageType.ALERT, alertMessageDTO.getMessageBody());
            // ?????????????????????
            pushAsyncClient.pushAttachMessage(AttachMessageDTO.buildNewNotificationMessage(uid));
        }
        if (alertMessageDTO.getAlert() > 0) {
            try {
                ServerResult serverResult = doPush(originPushType, buildPushAlertMessage(CommonUtil.getEnvironmentName(env).concat("_").concat(uid), alertMessageDTO.getMessageBody()), alertMessageDTO);
                if (serverResult != null) {
                    return serverResult;
                }
            } catch (APIConnectionException e) {
                // ????????????
                alertMessageDTO.setStore(0);
                pushAsyncClient.pushAlertMessage(alertMessageDTO);
                logger.error("APIConnectionException ????????????, ?????????????????????, Error Message: {}", e.getMessage());
                Thread.sleep(100);
            } catch (APIRequestException e) {
                // ?????????????????????????????????
                if (e.getErrorCode() == 1011) {
                    // ????????????
                    logger.warn("???????????????, Result: ?????????????????????????????????????????????(???WEB), Message: {}", alertMessageDTO);
                } else {
                    logger.error("APIRequestException ????????????, Error Message: {}", e.getErrorMessage());
                }
            } catch (Exception e) {
                logger.error("????????????, Exception: " + e.getMessage(), e);
            }
        }
        return ServerResult.build(OperateStatus.SUCCESS);
    }

    @RpcServerMethod
    public ServerResult pushAttachMessage(@Validated AttachMessageDTO attachMessageDTO) throws InterruptedException {
        String uid = attachMessageDTO.getUid();
        OriginPushType originPushType = OriginPushType.getOriginPushType(attachMessageDTO.getOriginPushType());
        String originUid = "";
        if (originPushType == OriginPushType.INTERACTIVE) {
            originUid = attachMessageDTO.getOriginUid();
            if (StringUtils.isBlank(originUid)) {
                return ServerResult.buildFailureMessage("originUid????????????");
            }
        }
        AttachType attachType = AttachType.getAttachType(attachMessageDTO.getAttachType());
        if (attachType == null) {
            return ServerResult.buildFailureMessage("attachType???????????????");
        }
        if (attachMessageDTO.getStore() > 0) {
            // ?????????????????????
            userMessageOTS.asyncSaveUserMessage(uid, originPushType, originUid, MessageType.ATTACH, attachMessageDTO.getMessageBody());
            // ?????????????????????
            pushAsyncClient.pushAttachMessage(AttachMessageDTO.buildNewNotificationMessage(uid));
        }
        AttachMessage attachMessage = new AttachMessage();
        attachMessage.setAttachType(attachType.getType());
        attachMessage.setAttachBody(attachMessageDTO.getAttachBody());
        try {
            ServerResult serverResult = doPush(originPushType, buildPushAttachMessage(CommonUtil.getEnvironmentName(env).concat("_").concat(uid), attachMessage), attachMessageDTO);
            if (serverResult != null) {
                return serverResult;
            }
        } catch (APIConnectionException e) {
            // ????????????
            pushAsyncClient.pushAttachMessage(attachMessageDTO);
            logger.error("APIConnectionException ????????????, ?????????????????????, Error Message: {}", e.getMessage());
            Thread.sleep(100);
        } catch (APIRequestException e) {
            // ?????????????????????????????????
            if (e.getErrorCode() == 1011) {
                // ????????????
                logger.warn("???????????????, Result: ?????????????????????????????????????????????(???WEB), Message: {}", attachMessageDTO);
            } else {
                logger.error("APIRequestException ????????????, Error Message: {}", e.getErrorMessage());
            }
        } catch (Exception e) {
            logger.error("????????????, Exception: " + e.getMessage(), e);
        }
        return ServerResult.build(OperateStatus.SUCCESS);
    }

    private JPushClient buildJPushClient(boolean isProd, OriginPushType originPushType) {
        long timeToLive = jPushProperties.getTimeToLive().getOfficial();
        if (originPushType == OriginPushType.INTERACTIVE) {
            timeToLive = jPushProperties.getTimeToLive().getInteractive();
        } else if (originPushType == OriginPushType.SYSTEM) {
            timeToLive = jPushProperties.getTimeToLive().getSystem();
        }
        // ????????????
        ClientConfig clientConfig = ClientConfig.getInstance();
        clientConfig.setMaxRetryTimes(3);
        clientConfig.setConnectionTimeout(5 * 1000);
        clientConfig.setConnectionRequestTimeout(5 * 1000);
        clientConfig.setSocketTimeout(5 * 1000);
        clientConfig.setGlobalPushSetting(isProd, timeToLive);
        // ?????????????????????
        return new JPushClient(jPushProperties.getMasterSecret(), jPushProperties.getAppKey(), null, clientConfig);
    }

    private ServerResult doPush(OriginPushType originPushType, PushPayload pushPayload, PushMessageDTO pushMessageDTO) throws APIConnectionException, APIRequestException, InterruptedException {
        boolean isProd = CommonUtil.isDevEnvironment(env);
        JPushClient jPushClient = buildJPushClient(isProd, originPushType);
        PushResult result = jPushClient.sendPush(pushPayload);
        if (!result.isResultOK()) {
            logger.error("????????????, Error Message: {}, statusCode: {}", result.getOriginalContent(), result.statusCode);
            return ServerResult.build(OperateStatus.SUCCESS);
        }
        logger.info("????????????, Result: {}, Message: {}", result, pushMessageDTO);
        Thread.sleep(100);
        jPushClient.close();
        // ????????????????????????????????????????????????
        if (isProd && jPushProperties.getWhiteUidList().contains(pushMessageDTO.getUid())) {
            jPushClient = buildJPushClient(false, originPushType);
            result = jPushClient.sendPush(pushPayload);
            if (!result.isResultOK()) {
                logger.error("????????????, Error Message: {}, statusCode: {}", result.getOriginalContent(), result.statusCode);
                return ServerResult.build(OperateStatus.SUCCESS);
            }
            logger.info("????????????, Result: {}, Message: {}", result, pushMessageDTO);
            Thread.sleep(100);
            jPushClient.close();
        }
        return null;
    }

    // ????????????
    private PushPayload buildPushAlertMessage(String alias, JSONObject messageBody) {
        return PushPayload.newBuilder()
                .setPlatform(Platform.android_ios())
                .setAudience(Audience.alias(alias))
                .setNotification(Notification.newBuilder()
                        .addPlatformNotification(IosNotification.newBuilder()
                                .setAlert(IosAlert.newBuilder().setTitleAndBody("", messageBody.getString("title"), messageBody.getString("content")).build())
                                .setSound("default")
                                .setContentAvailable(true)
                                .setMutableContent(true)
                                .incrBadge(1)
                                .addExtra("url", messageBody.getString("url"))
                                .build())
                        .addPlatformNotification(AndroidNotification.newBuilder()
                                .setTitle(messageBody.getString("title"))
                                .setAlert(messageBody.getString("content"))
                                .setStyle(1)
                                .setBigText(messageBody.getString("content"))
                                .addExtra("url", messageBody.getString("url"))
                                .build())
                        .build())
                .build();
    }

    // ????????????
    private PushPayload buildPushAttachMessage(String alias, AttachMessage attachMessage) {
        return PushPayload.newBuilder()
                .setPlatform(Platform.android_ios())
                .setAudience(Audience.alias(alias))
                .setNotification(Notification.newBuilder()
                        .addPlatformNotification(IosNotification.newBuilder()
                                .setAlert("")
                                .incrBadge(0)
                                .build())
                        .addPlatformNotification(AndroidNotification.newBuilder()
                                .setAlert("")
                                .build())
                        .build())
                .setMessage(Message.newBuilder()
                        .setMsgContent(JSON.toJSONString(attachMessage))
                        .build())
                .build();
    }

}
