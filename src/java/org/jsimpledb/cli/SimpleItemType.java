
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb.cli;

import com.google.common.reflect.TypeToken;

import java.io.IOException;

public class SimpleItemType<T> implements ItemType<T> {

    private final TypeToken<T> typeToken;

    public SimpleItemType(Class<T> type) {
        this(TypeToken.of(type));
    }

    public SimpleItemType(TypeToken<T> typeToken) {
        if (typeToken == null)
            throw new IllegalArgumentException("null typeToken");
        this.typeToken = typeToken;
    }

    @Override
    public TypeToken<T> getTypeToken() {
        return this.typeToken;
    }

    @Override
    public void print(Session session, T value) throws IOException {
        session.getWriter().println("" + value);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != this.getClass())
            return false;
        final SimpleItemType<?> that = (SimpleItemType<?>)obj;
        return this.typeToken.equals(that.typeToken);
    }

    @Override
    public int hashCode() {
        return this.typeToken.hashCode();
    }
}

