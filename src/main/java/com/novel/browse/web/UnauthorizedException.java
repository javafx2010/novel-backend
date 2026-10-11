package com.novel.browse.web;

import com.novel.browse.i18n.CodedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Authentication failed; the message is a code resolved per request locale */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnauthorizedException extends CodedException {

    public UnauthorizedException(String code, Object... args) {
        super(code, args);
    }
}
