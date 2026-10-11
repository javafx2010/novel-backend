package com.novel.browse.service;

import com.novel.browse.i18n.CodedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Invalid parameter or uploaded content; the message is a code resolved per request locale */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BadRequestException extends CodedException {

    public BadRequestException(String code, Object... args) {
        super(code, args);
    }
}
