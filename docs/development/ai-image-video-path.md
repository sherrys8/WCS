# AI识别：视频文件路径解析

## 现象

对视频消息点「AI识别」，等约 15 秒后报「视频还没下载完」。日志里 `imagePath` 指向的文件在
`/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/<hash>/video/…`，`File.isFile` 为 false。

## 根因

微信写进消息的 `imagePath` 是**外部存储镜像路径**，其中 `<hash>` 与真实账号目录不同，该路径在真机上
根本不存在；真文件在内部账号目录：

```
/data/user/0/com.tencent.mm/MicroMsg/<32 位账号 hash>/video/xx/yy/<name>.mp4
```

图片/表情链路一直是「VFS 读 → 落临时文件」，所以没暴露；视频是唯一直接 `File.isFile` 的分支，
于是「等 15 秒」等的是一个永远不会出现的路径。

## 修法

`WeMessageApi.materializeVideoSource(rawPath, destination, maxBytes)` 依次尝试：

1. 原路径直读（`File.isFile && length > 0`）
2. 把 `/MicroMsg/<任意hash>/` 之后的部分拼到账号目录（`files/MicroMsg/` 下最近修改的 32 位目录）后直读
3. 走微信 VFS 读流，流式拷到 `cache/WcS/ai-image-recognition/video-*.mp4`（边拷边判上限）

返回 `VideoSource.Ready / TooLarge / Missing`。等待下载由调用方按 deadline 轮询，函数本身不阻塞。

## 两个顺带的约束

- **上限从 20 MB 收到 10 MB**：base64 会让请求体膨胀约 4/3，20 MB 视频顶到 ~27 MB，多数网关直接拒。
- **请求体流式写出**：`writeVideoRequest` 用 12 KiB（3 的倍数）分块 base64，块间用 `NO_PADDING`，
  只有末块带填充。原来的 `readBytes() + encodeToString` 会同时压着原始字节、编码串、JSON 串三份内存。

## 排查口诀

看到 `视频识别: 采用 …` 说明已解析成功（直读或 VFS 落盘）；只有 `视频识别: 未找到视频文件` 才是真缺文件，
此时把日志里的 `imagePath` 和 `searched` 拿出来，`su -c ls` 一下账号目录对应路径即可判断是路径问题还是没下载。
