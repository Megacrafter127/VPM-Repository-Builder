package net.m127.json.vpm;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.Getter;

import java.util.Map;

@Data
public class AuthorJSON {
    private String name;
    private String email;
    
    @JsonIgnore
    @JsonAnySetter
    @Getter(onMethod_ = @JsonAnyGetter)
    private Map<String, Object> unknown;
}
