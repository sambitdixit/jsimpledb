
/*
 * Copyright (C) 2014 Archie L. Cobbs. All rights reserved.
 *
 * $Id$
 */

package org.jsimpledb.jlayer;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import org.jsimpledb.CollectionField;
import org.jsimpledb.schema.CollectionSchemaField;

/**
 * Represents a collection field in a {@link JClass}.
 */
public abstract class JCollectionField extends JComplexField {

    final JSimpleField elementField;

    JCollectionField(String name, int storageId, JSimpleField elementField, String description, Method getter) {
        super(name, storageId, description, getter);
        if (elementField == null)
            throw new IllegalArgumentException("null elementField");
        this.elementField = elementField;
    }

    @Override
    public List<JSimpleField> getSubFields() {
        return Collections.<JSimpleField>singletonList(this.elementField);
    }

    @Override
    public JSimpleField getSubField(String name) {
        if (CollectionField.ELEMENT_FIELD_NAME.equals(name))
            return this.elementField;
        throw new IllegalArgumentException("unknown sub-field `"
          + name + "' (did you mean `" + CollectionField.ELEMENT_FIELD_NAME + "' instead?)");
    }

    @Override
    String getSubFieldName(JSimpleField subField) {
        if (subField == this.elementField)
            return CollectionField.ELEMENT_FIELD_NAME;
        throw new IllegalArgumentException("unknown sub-field");
    }

    @Override
    abstract CollectionSchemaField toSchemaItem();

    void initialize(CollectionSchemaField schemaField) {
        super.initialize(schemaField);
        schemaField.setElementField(this.elementField.toSchemaItem());
    }
}

