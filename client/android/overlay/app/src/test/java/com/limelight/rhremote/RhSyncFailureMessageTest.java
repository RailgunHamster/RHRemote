package com.limelight.rhremote;

import java.io.*;
import java.net.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Test;
import static org.junit.Assert.*;

/** User-visible transport errors, independently of credentials and service access. */
public final class RhSyncFailureMessageTest {
    @Test public void resetUsesAReadableMessageWithoutRepeatingTheRawEndpoint(){
        String error=RhSync.failureMessage(new SocketException("Connection reset on http://example.invalid/private-path"));
        assertEquals("WebDAV 连接已中断，请检查网络后重试",error);
        assertFalse(error.contains("example.invalid"));
    }
    @Test public void refusedAndUnreachableHaveDifferentAdvice(){
        assertEquals("WebDAV 连接失败，请检查地址和网络后重试",RhSync.failureMessage(new ConnectException("ECONNREFUSED")));
        assertEquals("WebDAV 地址不可达，请检查网络和路由",RhSync.failureMessage(new NoRouteToHostException("EHOSTUNREACH")));
    }
    @Test public void earlyEofAndWrappedResetAreNotRawEnglish(){
        assertEquals("WebDAV 连接提前结束，请检查网络后重试",RhSync.failureMessage(new EOFException("unexpected end of stream")));
        assertEquals("WebDAV 连接已中断，请检查网络后重试",RhSync.failureMessage(new IOException("transport failed",new SocketException("Connection reset"))));
    }
    @Test public void tlsTimeoutAndNameErrorsKeepTheirSpecificMessages(){
        assertEquals("WebDAV 证书或 TLS 连接验证失败，请检查证书和地址",RhSync.failureMessage(new IOException("wrapped",new SSLHandshakeException("certificate verify failed"))));
        assertEquals("WebDAV 连接超时，请检查网络后重试",RhSync.failureMessage(new SocketTimeoutException("timeout")));
        assertEquals("无法解析 WebDAV 地址，请检查地址和网络",RhSync.failureMessage(new UnknownHostException("example.invalid")));
    }
    @Test public void lockReleaseHttpAndLocalStorageMessagesArePreserved(){
        String release="WebDAV 同步保护未能释放，请稍后手动同步核对";
        assertEquals(release,RhSync.failureMessage(new IOException(release,new SocketException("Connection reset"))));
        assertEquals("WebDAV 登录失败，请检查账户和权限（HTTP 401）",RhSync.failureMessage(new IOException("WebDAV HTTP 401")));
        assertEquals("WebDAV 文件被占用，请稍后重试（HTTP 423）",RhSync.failureMessage(new IOException("WebDAV HTTP 423")));
        assertEquals("本机同步配置写入失败",RhSync.failureMessage(new IOException("本机同步配置写入失败")));
    }
}
