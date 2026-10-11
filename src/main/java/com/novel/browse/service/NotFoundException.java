package com.novel.browse.service;

import com.novel.browse.i18n.CodedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Requested entity does not exist; the message is a code resolved per request locale */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends CodedException {

    public NotFoundException(String code, Object... args) {
        super(code, args);
    }
}
