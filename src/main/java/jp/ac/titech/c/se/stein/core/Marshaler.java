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
public interface Marshaler<T> {
    Logger log = LoggerFactory.getLogger(Marshaler.class);

    /**
     * Marshals an object and write it to the given stream.
     */
    void writeObject(final T object, final OutputStream stream);

    /**
     * Reads from the given stream and unmarshals it to an object.
     */
    T readObject(final InputStream stream);

    /**
     * Marshals an object.
     */
    default byte[] marshal(final T object) {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream();
        writeObject(object, stream);
        return stream.toByteArray();
    }

    /**
     * Unmarshals an object.
     */
    default T unmarshal(final byte[] binary) {
        return readObject(new ByteArrayInputStream(binary));
    }

    class JavaSerializerMarshaler<T> implements Marshaler<T> {
        @Override
        public void writeObject(final T object, final OutputStream stream) {
            try (final ObjectOutputStream output = new ObjectOutputStream(stream)) {
                output.writeObject(object);
            } catch (final IOException e) {
                log.error(e.getMessage(), e);
            }
        }

        @Override
        public T readObject(final InputStream stream) {
            try (final ObjectInputStream input = new ObjectInputStream(stream)) {
                @SuppressWarnings("unchecked")
                final T result = (T) input.readObject();
                return result;
            } catch (final IOException | ClassNotFoundException e) {
                log.error(e.getMessage(), e);
                return null;
            }
        }
    }

    class ObjectIdMarshaler implements Marshaler<ObjectId> {
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
