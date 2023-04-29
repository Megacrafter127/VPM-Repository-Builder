package net.m127.json.vpm;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class VersionJSON {
    public static <R> VersionJSON newEmpty(R ignored) {
        return new VersionJSON();
    }
    private Map<String, PackageJSON> versions = new HashMap<>();
}
