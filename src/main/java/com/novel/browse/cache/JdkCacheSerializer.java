package com.novel.browse.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;

/**
 * 缓存条目序列化：使用 JDK 原生序列化，缓存对象需实现 {@link java.io.Serializable}。
 */
final class JdkCacheSerializer {

    private static final Logger log = LoggerFactory.getLogger(JdkCacheSerializer.class);

    private JdkCacheSerializer() {
    }

    static byte[] serialize(Object value) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            oos.flush();
            return bos.toByteArray();
        }
    }

    static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        }
    }

    static UncheckedIOException wrap(String message, IOException e) {
        log.warn(message, e);
        return new UncheckedIOException(message, e);
    }
}
