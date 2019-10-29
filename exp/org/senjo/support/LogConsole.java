/* Copyright 2019, Senjo Org. Denis Rezvyakov aka Dinya Feony Senjo.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package org.senjo.support;

import static org.senjo.basis.Text.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.senjo.annotation.*;
import org.senjo.support.Log;

/**
 * 
 * @author Denis Rezvyakov aka Dinya Feony Senjo
 * @version create 2019-03-14, experimental */
public final class LogConsole extends Log {
	public static void initDefault(Level limit) { initialize(new LogConsole(limit)); }

	public LogConsole(Level limit) { super(Level.Easy.ordinal(), limit.ordinal()); }

	public static LogConsole get() {
		Log result = instance();
		if (result instanceof LogConsole) return (LogConsole)result;
		initialize(new LogConsole(Level.Easy));
		return (LogConsole)instance();
	}

	private static int threadWidth = 8, classWidth = 8, methodWidth = 8;
	private long timeOffset = 0;

	private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
			.withZone(ZoneId.systemDefault());

	public LogConsole width(int threadName, int className, int methodName) {
		threadWidth = threadName;
		 classWidth =  className;
		methodWidth = methodName;
		return this; }

	public LogConsole relative() {
		timeOffset = System.currentTimeMillis();
		formatter = formatter.withZone(ZoneOffset.UTC);
		return this; }

	private static String crop(String source, int length, char beginChar) {
		if (beginChar != 0) {
			int position = source.lastIndexOf(beginChar);
			if (position >= 0) source = source.substring(position+1); }
		return length < source.length() ? source.substring(0, length-1) + '…'
				: align(source, length, 0f); }

	@Override protected void log( @NotNull Level level, @NotNull StackTraceElement point,
			@Nullable String message, @Nullable Throwable ex ) {
		long instant = System.currentTimeMillis();
		String threadName = Thread.currentThread().getName();
		String time       = formatter.format(Instant.ofEpochMilli(instant - timeOffset));

		System.out.printf( "%s [%s|%s|%s%3d] %s: %s%s\n", time,
				crop(threadName           , threadWidth, '\0'),
				crop(point.getClassName (),  classWidth,  '.'),
				crop(point.getMethodName(), methodWidth, '\0'),
				point.getLineNumber(),
				level.text, message, thrownText(ex) );
	}

	private final String thrownText(@Nullable Throwable target) {
		if (target == null) return "";
		if (target instanceof Stack) {
			StringBuilder out = new StringBuilder(256).append('\n');
			((Stack)target).printStack(out);
			return out.toString();
		} else {
			StringWriter writer = new StringWriter(512).append('\n');
			target.printStackTrace(new PrintWriter(writer));
			return writer.toString();
		}
	}
}


