
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.core;

import com.google.common.base.Preconditions;

import org.jsimpledb.schema.CounterSchemaField;
import org.jsimpledb.schema.EnumSchemaField;
import org.jsimpledb.schema.ListSchemaField;
import org.jsimpledb.schema.MapSchemaField;
import org.jsimpledb.schema.ReferenceSchemaField;
import org.jsimpledb.schema.SchemaFieldSwitchAdapter;
import org.jsimpledb.schema.SetSchemaField;
import org.jsimpledb.schema.SimpleSchemaField;

/**
 * Builds {@link Field}s from {@link SchemaField}s.
 */
class FieldBuilder extends SchemaFieldSwitchAdapter<Field<?>> {

    final Schema schema;
    final FieldTypeRegistry fieldTypeRegistry;

    FieldBuilder(Schema schema, FieldTypeRegistry fieldTypeRegistry) {
        Preconditions.checkArgument(schema != null, "null schema");
        Preconditions.checkArgument(fieldTypeRegistry != null, "null fieldTypeRegistry");
        this.schema = schema;
        this.fieldTypeRegistry = fieldTypeRegistry;
    }

// SchemaFieldSwitchAdapter

    @Override
    public SetField<?> caseSetSchemaField(SetSchemaField field) {
        return this.buildSetField(field, (SimpleField<?>)field.getElementField().visit(this));
    }

    @Override
    public ListField<?> caseListSchemaField(ListSchemaField field) {
        return this.buildListField(field, (SimpleField<?>)field.getElementField().visit(this));
    }

    @Override
    public MapField<?, ?> caseMapSchemaField(MapSchemaField field) {
        return this.buildMapField(field,
          (SimpleField<?>)field.getKeyField().visit(this), (SimpleField<?>)field.getValueField().visit(this));
    }

    @Override
    public SimpleField<?> caseSimpleSchemaField(SimpleSchemaField field) {
        final String fieldTypeName = field.getType();
        final long signature = field.getEncodingSignature();
        final FieldType<?> fieldType = this.fieldTypeRegistry.getFieldType(fieldTypeName, signature);
        if (fieldType == null) {
            final StringBuilder buf = new StringBuilder("unknown field type `" + fieldTypeName + "'");
            if (signature != 0)
                buf.append(" with signature ").append(signature);
            buf.append(" for field `").append(field.getName()).append('\'');
            boolean foundAny = false;
            for (FieldType<?> otherFieldType : this.fieldTypeRegistry.getAll()) {
                if (otherFieldType.getName().equals(fieldTypeName)) {
                    if (!foundAny) {
                        buf.append(" (note: field type(s) named `").append(fieldTypeName)
                          .append("' exist but with different signature(s): ");
                        foundAny = true;
                    } else
                        buf.append(", ");
                    buf.append(otherFieldType.getEncodingSignature());
                }
            }
            if (foundAny)
                buf.append(')');
            throw new IllegalArgumentException(buf.toString());
        }
        return this.buildSimpleField(field, field.getName(), fieldType);
    }

    @Override
    public SimpleField<?> caseReferenceSchemaField(ReferenceSchemaField field) {
        Preconditions.checkArgument(field.getEncodingSignature() == 0, "encoding signature must be zero");
        return new ReferenceField(field.getName(), field.getStorageId(), this.schema, field.getOnDelete(),
          field.isCascadeDelete(), field.isAllowDeleted(), field.isAllowDeletedSnapshot(), field.getObjectTypes());
    }

    @Override
    public EnumField caseEnumSchemaField(EnumSchemaField field) {
        Preconditions.checkArgument(field.getEncodingSignature() == 0, "encoding signature must be zero");
        return new EnumField(field.getName(), field.getStorageId(), this.schema, field.isIndexed(), field.getIdentifiers());
    }

    @Override
    public CounterField caseCounterSchemaField(CounterSchemaField field) {
        return new CounterField(field.getName(), field.getStorageId(), this.schema);
    }

// Internal methods

    // This method exists solely to bind the generic type parameters
    private <T> SimpleField<T> buildSimpleField(SimpleSchemaField field, String fieldName, FieldType<T> fieldType) {
        assert field.getEncodingSignature() == fieldType.getEncodingSignature();
        return new SimpleField<>(fieldName, field.getStorageId(), this.schema, fieldType, field.isIndexed());
    }

    // This method exists solely to bind the generic type parameters
    private <E> SetField<E> buildSetField(SetSchemaField field, SimpleField<E> elementField) {
        return new SetField<>(field.getName(), field.getStorageId(), this.schema, elementField);
    }

    // This method exists solely to bind the generic type parameters
    private <E> ListField<E> buildListField(ListSchemaField field, SimpleField<E> elementField) {
        return new ListField<>(field.getName(), field.getStorageId(), this.schema, elementField);
    }

    // This method exists solely to bind the generic type parameters
    private <K, V> MapField<K, V> buildMapField(MapSchemaField field, SimpleField<K> keyField, SimpleField<V> valueField) {
        return new MapField<>(field.getName(), field.getStorageId(), this.schema, keyField, valueField);
    }
}

