package jp.ac.titech.c.se.stein.core;

import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

/**
 * Converts an object to a byte array and vice versa.
 */
public abstract class Marshaler<T> {
    private final static Logger log = LoggerFactory.getLogger(Marshaler.class);

    public abstract void writeObject(final T object, final OutputStream stream);

    public abstract T readObject(final InputStream stream);

    public byte[] marshal(final T object) {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeObject(object, stream);
        return stream.toByteArray();
    }

    public T unmarshal(final byte[] binary) {
        return readObject(new ByteArrayInputStream(binary));
    }

    public static class JavaSerializerMarshaler<T> extends Marshaler<T> {
        @Override
        public void writeObject(final T object, final OutputStream stream) {
            try (final ObjectOutputStream output = new ObjectOutputStream(stream)) {
                output.writeObject(object);
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public T readObject(final InputStream stream) {
            try (final ObjectInputStream input = new ObjectInputStream(stream)) {
                @SuppressWarnings("unchecked")
                final T result = (T) input.readObject();
                return result;
            } catch (final IOException | ClassNotFoundException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    public static class ObjectIdMarshaler extends Marshaler<ObjectId> {
        @Override
        public void writeObject(final ObjectId object, final OutputStream stream) {
            try {
                object.copyRawTo(stream);
            } catch (final IOException e) {
                log.error(e.getMessage(), e);
            }
        }

        @Override
        public ObjectId readObject(final InputStream stream) {
            try {
                final byte[] bytes = stream.readAllBytes();
                return ObjectId.fromRaw(bytes);
            } catch (final IOException e) {
                log.error(e.getMessage(), e);
                return null;
            }
        }
    }
}
