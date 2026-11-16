package com.dingjson.codec;

import com.dingjson.annotation.DingJsonSerializable;
import com.dingjson.annotation.JSONField;

import java.util.Date;
import java.util.List;

@DingJsonSerializable
public class CodecBean {
    public int id;
    public long big;
    public double score;
    public float ratio;
    public boolean flag;
    public char letter;
    public byte tiny;
    public short small;
    public String name;
    public Date created;
    @com.dingjson.annotation.JSONField(format = "yyyy-MM-dd")
    public Date birthday;
    public List<String> tags;
    public CodecChild child;
    @JSONField(name = "renamed")
    public String original;
    @JSONField(serialize = false)
    public String skippedOnWrite;
}
