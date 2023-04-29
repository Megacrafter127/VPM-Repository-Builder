package net.m127.json.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.regex.Pattern;

@Data
public class SourceRepository {
    private String cloneURL;
    private Pattern tagPattern = Pattern.compile("^(?<pkg>\\w++(?:\\.\\w++)*+)#(?<version>\\d++(?:\\.\\d++)*+)$");
    @JsonProperty("package")
    private String pkg = "${pkg}";
    private MismatchHandling onPackageMismatch = MismatchHandling.ERROR;
    private String version = "${version}";
    private MismatchHandling onVersionMismatch = MismatchHandling.WARN_REPLACE;
    private String path = "${pkg}";
}
