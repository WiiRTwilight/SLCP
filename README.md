# SLCP - Some Long Config Puller

**温馨提示：本项目使用生成式AI开发**

## 什么是 SLCP

SLCP 是一个用于从外部自动下载文件的 Fabric 模组，通过配置文件定义要下载的文件。本项目的本意是用于自动更新一些模组配置文件和多人游戏服务器列表`servers.dat`，故名为 Some Long Config Puller, 即"一些长配置拉取器"。

## 使用要求

- Java 21
- Minecraft 1.21.11
- Fabric Loader 0.19.2+
- Fabric API 0.141.0+

## 配置

打开游戏实例或者服务器实例文件夹，有个`config`文件夹，打开找到里面的`slcp`文件夹，就能找到`config.json`, 按照如下格式进行配置：
```json
[
    {
        "LICENSE": {
            "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
            "output": "./config/slcp/",
            "isServerDat": false
        }
    },
        {
        "aaa": {
            "url": "https://exam.ple/something.file",
            "output": "./",
            "isServerDat": false
        }
    }
]
```

## 鸣谢

- [linyuyuan2010](https://github.com/linyuyuan2010)
