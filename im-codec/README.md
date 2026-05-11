# im-codec — 协议编解码层

自定义 TCP 二进制协议。

## 协议格式

```
Byte 0: Magic     (0xCC)
Byte 1: Version   (0x01)
Byte 2-3: Body Length (uint16, big-endian)
Byte 4+: Body     (JSON bytes)
```

## 文件清单

| 文件 | 职责 |
|------|------|
| `IMDecoder` | ByteBuf → IMCommand（TCP + WS 共享解码逻辑） |
| `IMEncoder` | IMCommand → ByteBuf |
| `ContentSerializer` | 消息体 JSON 序列化/反序列化 |
| `IMDecoderEncoderTest` | 编解码单元测试 |
| `ContentSerializerTest` | 序列化单元测试 |
