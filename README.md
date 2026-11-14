# InfiniteChat

InfiniteChat 是基于 Spring Boot 的即时通信后端，包含认证、网关、联系人、消息、实时通信、离线存储和动态服务。各模块由根目录的 Maven 项目管理，数据库结构见 [InfiniteChat.sql](InfiniteChat.sql)。

## 运行配置

启动服务前，在运行环境中设置对应的变量。使用 [Docker Compose](docker-compose.service.yml) 时，可把变量放在本地 `.env` 中。各服务的连接地址和端口位于各自的 `src/main/resources/application.yml`。

| 用途 | 环境变量 |
| --- | --- |
| Nacos | `INFINITECHAT_NACOS_PASSWORD` |
| 数据库 | `INFINITECHAT_DB_PASSWORD` |
| Redis | `INFINITECHAT_REDIS_PASSWORD` |
| JWT 签名 | `INFINITECHAT_TOKEN_SECRET_KEY`，各签发和验证服务使用同一个值 |
| 用户密码 | `INFINITECHAT_PASSWORD_SALT`，连接已有用户数据库时使用原值 |
| 短信 | `INFINITECHAT_SMS_ACCESS_KEY_ID`、`INFINITECHAT_SMS_ACCESS_KEY_SECRET` |
| MinIO | `INFINITECHAT_MINIO_ACCESS_KEY`、`INFINITECHAT_MINIO_SECRET_KEY` |
| Sentry（可选） | `INFINITECHAT_SENTRY_DSN` |
