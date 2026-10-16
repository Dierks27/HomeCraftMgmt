package org.mvplugins.multiverse.external.vavr.control;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A stand-in for vavr's {@code Option} as Multiverse-Core 5 ships it, shaded under its own package
 * ({@code relocate 'io.vavr', 'org.mvplugins.multiverse.external.vavr'}), with vavr 1.0.1's shape: an
 * interface with {@code isEmpty()}, {@code isDefined()}, {@code get()} and {@code getOrNull()}, and its
 * {@code Some} and {@code None} classes. It is iterable, as vavr's is. For {@code MvGameModeTest}.
 */
public interface Option<T> extends Iterable<T> {

    static <T> Option<T> of(T value) {
        return value == null ? none() : new Some<>(value);
    }

    @SuppressWarnings("unchecked")
    static <T> Option<T> none() {
        return (Option<T>) None.INSTANCE;
    }

    boolean isEmpty();

    T get();

    default boolean isDefined() {
        return !isEmpty();
    }

    default T getOrNull() {
        return isEmpty() ? null : get();
    }

    @Override
    default Iterator<T> iterator() {
        return isEmpty() ? java.util.Collections.emptyIterator() : java.util.List.of(get()).iterator();
    }

    final class Some<T> implements Option<T> {
        private final T value;

        private Some(T value) {
            this.value = value;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public String toString() {
            return "Some(" + value + ")";
        }
    }

    final class None<T> implements Option<T> {
        private static final None<?> INSTANCE = new None<>();

        private None() {
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public T get() {
            throw new NoSuchElementException("No value present");
        }

        @Override
        public String toString() {
            return "None";
        }
    }
}
