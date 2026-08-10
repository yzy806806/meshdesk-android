# MeshDesk Android

在 Android 手机上原生运行 [MeshDesk](https://github.com/yzy806806/meshdesk)（root，无需 chroot）。MeshDesk 是 Go 静态编译的网络 daemon，Android 可直接执行。

## 功能

- **一键下载安装** meshdesk 二进制（GitHub Releases 最新版，含 Android ip rule 修复）
- **启停控制**：root 启动/停止 daemon（干净环境，`env -i`）
- **配置管理**：config.yaml 编辑 + 保存重启
- **identity 生成**：一键生成 Ed25519 密钥对
- **状态显示**：运行状态 / 版本

## 架构

```
App (Kotlin + Compose)
  └─ RootShell (Magisk su, stdin 方式)
       └─ /data/adb/meshdesk/
           ├── meshdesk        # 二进制
           ├── config.yaml
           ├── identity.pem
           ├── meshdesk.pid
           └── meshdesk.log
```

- daemon 用 `env -i PATH=/sbin:/system/bin:...` 干净环境启动（避免 Android PATH 泄漏）
- TUN 由 meshdesk 自身管理（含 Android policy routing 修复 76ac88f）
- 配置默认：VIP 10.100.0.6、52888、DNS 禁用（第一版）

## 构建

GitHub Actions 自动构建，APK 见 Actions artifacts。

## 测试拓扑

| 节点 | VIP |
|------|-----|
| txcloud | .3 |
| aliyun | .1 |
| Redmi (本机) | .6 |
| N1 | .2 |
| Oracle AMD | .5 |
