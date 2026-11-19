package com.processing.gateway.downstream;

import org.springframework.web.client.ResourceAccessException;
import reactor.netty.http.client.PrematureCloseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

public final class DownstreamExceptionUtils {

    private DownstreamExceptionUtils() {
    }

    public static boolean isDownstreamUnavailable(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ResourceAccessException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof PrematureCloseException) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }

    public static void rethrow(Exception exception) throws IOException {
        if (exception instanceof IOException ioException) {
            throw ioException;
        }
        if (exception instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        throw new IOException(exception);
    }
}
