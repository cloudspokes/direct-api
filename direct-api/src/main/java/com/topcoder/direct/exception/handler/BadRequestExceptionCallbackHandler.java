/*
 * Copyright (C) 2014 TopCoder Inc., All Rights Reserved.
 */
package com.topcoder.direct.exception.handler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.appirio.tech.core.api.v2.exception.ExceptionContent;
import com.appirio.tech.core.api.v2.exception.handler.ExceptionCallbackHandler;
import com.topcoder.direct.exception.BadRequestException;

/**
 * Exception handler for bad requests.
 *
 * @author TCSASSEMBLER
 * @version 1.0
 * @since 1.0 (Topcoder Direct API - My Challenges API v1.0)
 */
@Component
public class BadRequestExceptionCallbackHandler implements ExceptionCallbackHandler {

    /**
     * True if the argument is an instance of {@link BadRequestException}, {@link IllegalArgumentException},
     * {@link IllegalStateException}.
     *
     * @param error the error to be handled
     * @return true for instances of {@link BadRequestException}, {@link IllegalArgumentException},
     *         {@link IllegalStateException}
     */
    public boolean isHandle(Throwable error) {
        return error instanceof BadRequestException || error instanceof IllegalArgumentException
            || error instanceof IllegalStateException;
    }

    /**
     * Creates the report for the exception, to be forwarded to the service output.
     *
     * @param th the underlying cause
     * @param req the service request
     * @param res the service response
     * @return the exception content
     */
    public ExceptionContent getExceptionContent(Throwable th, HttpServletRequest req, HttpServletResponse res) {
        ExceptionContent content = new ExceptionContent(th);
        content.setHttpStatus(HttpStatus.BAD_REQUEST);
        return content;
    }
}
