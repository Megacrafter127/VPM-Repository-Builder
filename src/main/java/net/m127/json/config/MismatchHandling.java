package net.m127.json.config;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogBuilder;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;

@RequiredArgsConstructor
public enum MismatchHandling {
    IGNORE(null),
    WARN_IGNORE(Logger::atWarn),
    ERROR(Logger::atError),
    FATAL(Logger::atFatal),
    WARN_REPLACE(Logger::atWarn),
    REPLACE(null);
    public final Function<Logger, LogBuilder> logBuilder;
}
