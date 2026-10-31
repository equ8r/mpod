package com.circpros.micahpod.network

import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Accepts JSON booleans, 0/1 numbers, and "0"/"1"/"true"/"false" strings.
 */
class FlexibleBooleanAdapter : TypeAdapter<Boolean>() {
    override fun write(out: JsonWriter, value: Boolean?) {
        if (value == null) {
            out.nullValue()
        } else {
            out.value(value)
        }
    }

    override fun read(reader: JsonReader): Boolean {
        return when (reader.peek()) {
            JsonToken.NULL -> {
                reader.nextNull()
                false
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NUMBER -> reader.nextInt() != 0
            JsonToken.STRING -> {
                val raw = reader.nextString().trim().lowercase()
                raw == "1" || raw == "true" || raw == "yes"
            }
            else -> {
                reader.skipValue()
                false
            }
        }
    }
}
