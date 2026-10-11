package com.novel.browse.i18n;

/**
 * Exception whose message is a code resolved against the message bundles in the locale of the current request.
 * <p>
 * Business code throws {@code new BadRequestException("error.novel.notFound", id)} instead of building a
 * literal string, so the same failure is returned in English, Chinese or any other configured language.
 */
public abstract class CodedException extends RuntimeException {

    private final String code;
    private final Object[] args;

    protected CodedException(String code, Object... args) {
        super(code);
        this.code = code;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args;
    }
}
