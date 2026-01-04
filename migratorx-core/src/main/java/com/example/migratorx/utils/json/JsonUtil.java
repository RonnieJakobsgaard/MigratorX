package com.example.migratorx.utils.json;

import com.example.migratorx.utils.json.exception.JsonParserException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class JsonUtil {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static <T> List<T> fromJsonFile(File jsonFile, Class<T[]> clazz) {
        try {
            T[] array = objectMapper.readValue(jsonFile, clazz);
            return Arrays.asList(array);
        } catch (Exception e) {
            throw new JsonParserException("Could not read json file", e);
        }
    }
}
