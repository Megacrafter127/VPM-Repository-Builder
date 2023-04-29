package net.m127.json.config;

import lombok.Data;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Data
public class Config {
    private String repositoryId;
    private String repositoryName;
    private String repositoryAuthor;
    private String baseURL;
    private Path buildFolder = Paths.get("dist");
    private List<SourceRepository> sources = new ArrayList<>();
}
