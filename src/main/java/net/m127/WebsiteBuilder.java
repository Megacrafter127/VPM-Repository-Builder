package net.m127;

import lombok.RequiredArgsConstructor;
import net.m127.json.config.Config;
import net.m127.json.vpm.IndexJSON;
import net.m127.json.vpm.VersionJSON;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

@RequiredArgsConstructor
public class WebsiteBuilder {
    private final Config config;
    
    public void buildIndexHTML(IndexJSON json) throws IOException {
        try(PrintWriter out = new PrintWriter(Files.newBufferedWriter(config.getBuildFolder().resolve("index.html"), StandardCharsets.UTF_8))) {
            out.println("<!DOCTYPE html>");
            out.println("<html>");
            out.println("  <body>");
            out.printf("    <h1>%s</h1>%n", json.getName());
            out.printf(
                "    <a href='%s'>%s</a>&nbsp;<a href='vcc://vpm/addRepo?url=%s'><button>Add to VCC</button></a>%n",
                json.getUrl(),
                json.getUrl(),
                URLEncoder.encode(json.getUrl(), "UTF-8")
            );
            for(Map.Entry<String, VersionJSON> e:json.getPackages().entrySet()) {
                out.println("    <hr>");
                out.printf("    <h2>%s</h2>%n", e.getKey());
                for(String version: e.getValue().getVersions().keySet()) {
                    out.printf("    <p>%s</p>%n", version);
                }
            }
            out.println("  </body>");
            out.println("</html>");
        }
    }
}
