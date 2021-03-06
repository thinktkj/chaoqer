package com.chaoqer.server;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.auth.sts.AssumeRoleRequest;
import com.aliyuncs.auth.sts.AssumeRoleResponse;
import com.chaoqer.common.entity.aliyun.ImageUploadDTO;
import com.chaoqer.common.entity.base.ImageDirType;
import com.chaoqer.common.util.CommonUtil;
import com.chaoqer.common.util.DigestUtil;
import com.chaoqer.common.util.RedisKeyGenerator;
import com.chaoqer.common.util.RedisUtil;
import com.chaoqer.entity.ImageUploadResult;
import com.chaoqer.entity.StsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import vip.toby.rpc.annotation.RpcServer;
import vip.toby.rpc.annotation.RpcServerMethod;
import vip.toby.rpc.entity.RpcType;
import vip.toby.rpc.entity.ServerResult;

import java.net.URL;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@RpcServer(value = "aliyun-image", type = RpcType.SYNC, threadNum = 4)
public class AliyunImageServer {

    private final static Logger logger = LoggerFactory.getLogger(AliyunImageServer.class);

    @Value("${aliyun.oss.endpoint}")
    private String ossEndpoint;
    @Value("${aliyun.oss.bucket-name}")
    private String ossBucketName;
    @Value("${aliyun.oss.host}")
    private String ossHost;
    @Value("${aliyun.oss.role-upload-arn}")
    private String ossRoleUploadArn;

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private IAcsClient stsClient;
    @Autowired
    private Environment env;

    @RpcServerMethod(allowDuplicate = true)
    public ServerResult createUpload(ImageUploadDTO imageUploadDTO) {
        ImageDirType imageDirType = ImageDirType.getName(imageUploadDTO.getImageDir());
        if (imageDirType == null) {
            return ServerResult.buildFailureMessage("imageDir???????????????");
        }
        if (imageDirType != ImageDirType.FEEDBACK && !imageUploadDTO.isUserLogin()) {
            return ServerResult.buildFailureMessage("????????????");
        }
        String userId = imageUploadDTO.getAuthedUid();
        String imageId = DigestUtil.getUUID();
        try {
            StsResult stsResult = getOssUploadRoleStsResult();
            if (stsResult != null) {
                String fileName = "image/".concat(userId).concat("/").concat(imageDirType.getName()).concat("/").concat(imageId).concat(".jpg");
                GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(ossBucketName, fileName, HttpMethod.PUT);
                // ??????????????????
                generatePresignedUrlRequest.setContentType("image/jpeg");
                // ??????URL????????????10??????
                generatePresignedUrlRequest.setExpiration(new Date(System.currentTimeMillis() + 10 * 60 * 1000L));
                // ????????????URL
                OSS oss = new OSSClientBuilder().build(ossEndpoint, stsResult.getAccessKeyId(), stsResult.getAccessKeySecret(), stsResult.getSecurityToken());
                URL url = oss.generatePresignedUrl(generatePresignedUrlRequest);
                oss.shutdown();
                ImageUploadResult imageUploadResult = new ImageUploadResult();
                imageUploadResult.setImageId(imageId);
                imageUploadResult.setUploadAddress(url.toString());
                imageUploadResult.setImageURL(ossHost.concat("/").concat(fileName));
                return ServerResult.buildSuccessResult(imageUploadResult);
            }
        } catch (Exception e) {
            logger.error("??????????????????????????????, {}", e.getMessage(), e);
        }
        return ServerResult.buildFailureMessage("??????????????????????????????");
    }

    /**
     * ??????sts??????
     */
    private StsResult getOssUploadRoleStsResult() {
        StsResult stsResult = RedisUtil.getObject(redisTemplate, RedisKeyGenerator.getOssUploadRoleStsKey(), StsResult.class);
        if (stsResult == null) {
            try {
                AssumeRoleRequest request = new AssumeRoleRequest();
                request.setSysRegionId("cn-shenzhen");
                request.setRoleArn(ossRoleUploadArn);
                request.setRoleSessionName(CommonUtil.getEnvironmentName(env));
                // 30???????????????
                request.setDurationSeconds(30 * 60L);
                AssumeRoleResponse response = stsClient.getAcsResponse(request);
                stsResult = new StsResult();
                stsResult.setAccessKeyId(response.getCredentials().getAccessKeyId());
                stsResult.setAccessKeySecret(response.getCredentials().getAccessKeySecret());
                stsResult.setSecurityToken(response.getCredentials().getSecurityToken());
                // ????????????29??????
                RedisUtil.setObject(redisTemplate, RedisKeyGenerator.getOssUploadRoleStsKey(), stsResult, 29, TimeUnit.MINUTES);
                return stsResult;
            } catch (Exception e) {
                logger.error("??????oss upload sts????????????, {}", e.getMessage(), e);
            }
        }
        return stsResult;
    }

}
