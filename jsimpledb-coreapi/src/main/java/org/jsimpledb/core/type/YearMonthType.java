
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.core.type;

import com.google.common.base.Preconditions;

import java.time.YearMonth;
import java.util.regex.Pattern;

import org.jsimpledb.util.ByteReader;
import org.jsimpledb.util.ByteWriter;
import org.jsimpledb.util.LongEncoder;
import org.jsimpledb.util.ParseContext;

/**
 * Non-null {@link YearMonth} type. Null values are not supported by this class.
 */
public class YearMonthType extends NonNullFieldType<YearMonth> {

    private static final Pattern PATTERN = Pattern.compile("-?[0-9]+-[0-9]+");

    private static final long serialVersionUID = 2773124141026846109L;

    public YearMonthType() {
        super(YearMonth.class, 0);
    }

// FieldType

    @Override
    public YearMonth read(ByteReader reader) {
        Preconditions.checkArgument(reader != null);
        return YearMonth.of((int)LongEncoder.read(reader), (int)LongEncoder.read(reader));
    }

    @Override
    public void write(ByteWriter writer, YearMonth yearMonth) {
        Preconditions.checkArgument(yearMonth != null, "null yearMonth");
        Preconditions.checkArgument(writer != null);
        LongEncoder.write(writer, yearMonth.getYear());
        LongEncoder.write(writer, yearMonth.getMonthValue());
    }

    @Override
    public void skip(ByteReader reader) {
        Preconditions.checkArgument(reader != null);
        reader.skip(LongEncoder.decodeLength(reader.peek()));
        reader.skip(LongEncoder.decodeLength(reader.peek()));
    }

    @Override
    public YearMonth fromParseableString(ParseContext ctx) {
        return YearMonth.parse(ctx.matchPrefix(YearMonthType.PATTERN).group());
    }

    @Override
    public String toParseableString(YearMonth yearMonth) {
        return yearMonth.toString();
    }

    @Override
    public int compare(YearMonth yearMonth1, YearMonth yearMonth2) {
        return yearMonth1.compareTo(yearMonth2);
    }

    @Override
    public boolean hasPrefix0x00() {
        return false;
    }

    @Override
    public boolean hasPrefix0xff() {
        return false;
    }
}

