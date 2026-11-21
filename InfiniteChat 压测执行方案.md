# InfiniteChat 压测执行方案

整个压测过程需要接近十个小时，如未有连续的时间，非常不建议进行实践，看文档即可。

因为24小时内未压测完红包将过期，需要重新配置环境，重装一遍中间件环境，然后重新来过。

本文面向第一次压测项目的人。按顺序做完 章节1～章节8，就能跑完「领取红包」与「单聊发送消息」两个场景的完整压测，并得到可信结论。

**规定：**

- 所有宿主机命令在 **Windows PowerShell ****7** 里执行（不是 cmd）。

- 每一步后面都有「确认」：给出预期输出，不符合就停下来，不要继续。

压测环境示例：Windows 11，16C 32G 本地环境

## 环境准备

### 工具和源码下载：

Jmter下载：

\[apache\-jmeter\-5\.6\.3\.zip\]

> 默认位置：D:\\Program Files
> 
> 

Jmter 插件下载：

\[websocket\-samplers\-1\.3\.2\.zip\]

> 默认位置：D:\\Program Files\\apache\-jmeter\-5\.6\.3
> 
> 

Powershell 7 下载：（Powershell 执行命令）

```PowerShell
winget search --id Microsoft.PowerShell --exact

winget install --id Microsoft.PowerShell --source winget
```

源码下载：（已打包）

\[InfiniteChat\.zip\]

> 修改：四服务补 actuator\+micrometer，新增 ImMetrics 及三条 Kafka 时延 Timer；WebSocket 握手与网关加鉴权开关；清除全部 20 处 System\.out、降级高频日志、新增 perf 异步 logback；Redis 统一 db0；声明 6 分区、加消费并发、关自动提交并接 DLT；Canal 表名小写匹配、群成员加缓存；五份 perf profile。全量编译通过。
> 
> 默认位置：D:\\Project\\InfiniteChat
> 
> 

脚本及 Jmter 压测脚本下载：

\[附件\.zip\]

> 默认位置：C:\\perf\\infinitechat
> 
> 

### 中间件重新部署（限制内存）

[压测中间件环境搭建](https://q0kyes8lnt5.feishu.cn/wiki/M8jLw0JPxibBOAk5huCcBMEhnBe)

### 准备一份公共变量文件（后面所有脚本都引用它）

**为什么**：容器名不在代码库里，由你的 Docker Desktop 决定；密码和端口在 yml 里。把它们集中到一个文件，后面每个脚本先 `. .\perf-env.ps1` 加载即可。

1. 先看容器名：

```PowerShell
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Ports}}"
```

2. 建工作目录并写入 `perf-env.ps1`：

```PowerShell
New-Item -ItemType Directory -Force C:\perf\infinitechat | Out-Null
Set-Location C:\perf\infinitechat
@'
# ===== 只改这 5 行：填 docker ps 里看到的容器名 =====
$MYSQL_C = "mysql"
$REDIS_C = "redis"
$KAFKA_C = "kafka"
$NACOS_C = "nacos"
$CANAL_C = "canal-server"

# ===== 以下来自各模块 application.yml，不要改 =====
$MYSQL_HOST_PORT = 49152                 # spring.datasource.url
$MYSQL_DB        = "InfiniteChat"
$MYSQL_USER      = "root"
$MYSQL_PWD       = "gK3T9n%q2M@j7Z4"
$MYSQL_ENV       = "MYSQL_PWD=$MYSQL_PWD" # 通过环境变量传密码，避免命令行警告
$REDIS_HOST_PORT = 59000                 # spring.data.redis.port
$REDIS_PWD       = "e65K4t8w2"           # spring.data.redis.password，所有服务统一 db 0
$KAFKA_HOST_PORT = 9092                  # spring.kafka.bootstrap-servers
$KAFKA_BOOT_IN   = "localhost:9092"      # 容器内部访问 broker 的地址；若 kafka-topics --list 报错，改成容器内监听地址（如 kafka:9092 / localhost:29092）
$NACOS_HOST_PORT = 18375
$CANAL_HOST_PORT = 11111                 # OfflineDataService canal.server.port

# 服务端口（server.port）与 Netty 端口（NettyService.port）
$P_OFFLINE  = 8101
$P_REALTIME = 8102
$P_REDPACKET= 8103
$P_USER     = 8104
$P_NETTY    = 9101

# JMeter
$JMETER_HOME = "D:\Program Files\apache-jmeter-5.6.3"   # 改成你的 JMeter 目录

# ===== 辅助函数 =====
function Invoke-MySql([string]$sql) {
  docker exec -e $MYSQL_ENV $MYSQL_C mysql "-u$MYSQL_USER" -N -B $MYSQL_DB -e $sql 
}
function Invoke-MySqlFile([string]$path) {
  Get-Content $path -Raw | docker exec -i -e $MYSQL_ENV $MYSQL_C mysql "-u$MYSQL_USER" $MYSQL_DB
}
function Invoke-Redis {
  param([Parameter(ValueFromRemainingArguments=$true)][string[]]$cmd)
  docker exec $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0 @cmd
}
# Kafka 脚本路径因镜像而异（bitnami / apache / confluent），自动探测一次
$KAFKA_BIN = docker exec $KAFKA_C sh -c 'for d in /opt/bitnami/kafka/bin /opt/kafka/bin /kafka/bin /usr/bin; do if [ -x "$d/kafka-topics.sh" ]; then echo "$d/"; exit 0; fi; done; if command -v kafka-topics >/dev/null 2>&1; then echo CONFLUENT; fi'
function Invoke-Kafka([string]$tool, [string]$argLine) {
  if ($KAFKA_BIN -eq "CONFLUENT") { $c = "$tool $argLine" } else { $c = "${KAFKA_BIN}$tool.sh $argLine" }
  docker exec $KAFKA_C sh -c $c
}
'@ | Set-Content -Encoding UTF8 .\perf-env.ps1
. .\perf-env.ps1
"KAFKA_BIN = $KAFKA_BIN"
```

**确认**：最后一行打印出 `KAFKA_BIN = /opt/bitnami/kafka/bin/`（或 `/opt/kafka/bin/`、`CONFLUENT`）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MTFlNTJjMDU2ZmU4MzE4ZjdkMzBkZTFmM2E2OGYyZmJfNGQzNmMxNTRmNTA3ODlhNGUzNzYwZjA0YTg3NWM2M2JfSUQ6NzY4NTgwMjMxNDA5OTIzMTk0N18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

> 注意：这行输出来自上面命令块最后的 `"KAFKA_BIN = $KAFKA_BIN"`，它**不在** `perf-env.ps1` 文件里。文件本身只定义变量和函数，后面每一步用 `. .\perf-env.ps1` 加载它时**什么都不会打印，这是正常的**（脚本静默加载，避免采集脚本每次都刷屏）。任何时候想确认已加载，单独输入 `$KAFKA_BIN` 回车即可看到值。如果是空的，说明 `$KAFKA_C` 名字填错或镜像的 Kafka 脚本不在常见目录，`docker exec $KAFKA_C find / -name "kafka-topics*" 2>/dev/null` 找到后把目录写进 `$KAFKA_BIN` 探测列表。
> 
> 



所有命令在 **PowerShell 7** 里执行，工作目录固定 `C:\perf\infinitechat`。窗口报「无法在当前系统上运行此脚本。」时，先敲一次（只对当前窗口生效）：

```PowerShell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MjE2ZjQ4MTZlZjlhMjhhOWY1Mzk4Yzc4YWQ1NGY1OWFfZjI3NzIwNmMzMDhjMWFmZjNhMjJmMDllNDYwODY1ODRfSUQ6NzcwMzQ3MDEyMDQ3NjExODIyMF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)



## 环境检查

### 1\.1 容器在线 \+ 镜像 tag

**为什么**：五个中间件缺一个，某个服务就起不来（OfflineDataService 启动时就连 Canal，连不上直接失败）。

```PowerShell
. .\perf-env.ps1
foreach ($c in $MYSQL_C,$REDIS_C,$KAFKA_C,$NACOS_C,$CANAL_C) {
  $st  = docker inspect --format '{{.State.Status}}' $c 2>$null
  $img = docker inspect --format '{{.Config.Image}}' $c 2>$null
  "{0,-12} {1,-8} {2}" -f $c, $st, $img
}
```

**确认**：5 行都是 `running`，并把镜像 tag 记进报告（章节8）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=YWFhMWY5MzkxZGMwYzYzZjI1M2ZkYmQ1MWM5ODc0ZmRfODNkYjAyZGE3MTFlM2Q4YjFhZDM2MWMxNmZhMTE0Y2JfSUQ6NzY4NTgwMjYwMDE5NjgwMzU1Ml8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.2 WSL2 资源配额（先写 `.wslconfig` 再检查）**（可选）**

**为什么**：所有中间件跑在 Docker Desktop 的 WSL2 虚拟机里，它拿到多少 CPU/内存直接决定 Redis/Kafka/MySQL 的上限，是报告必填的环境信息。更重要的是：WSL2 **默认最多拿宿主 50% 内存（32G 机器 = 16 GB）**，而且 Linux page cache 用过不还，`vmmem` 只涨不降；再加上 4 个服务、IDEA、JMeter，整机内存一被吃穿，Windows 就开始压缩内存、写页面文件，压测数字全部作废。所以先给 Docker VM 定死上限。

1. 写 `.wslconfig`（PowerShell；`processors` 取逻辑核数的一半左右，8 核机器填 4）：

```PowerShell
@'
[wsl2]
memory=8GB          # 5 个中间件容器 mem_limit 合计 6 GB，8 GB 足够
processors=6        # 给一半左右的逻辑核，剩下留给 4 个服务和 JMeter
swap=0              # 不让 WSL 再去磁盘换页拖慢整机

[experimental]      # 需要 wsl --version ≥ 2.0（Win11）；Win10 把这一段删掉
autoMemoryReclaim=gradual   # 空闲时把 page cache 还给 Windows
sparseVhd=true
'@ | Set-Content -Encoding ASCII "$env:USERPROFILE\.wslconfig"
wsl --shutdown
```

然后重启 Docker Desktop（托盘图标 → Restart）。`wsl --shutdown` 会把所有容器一起停掉，所以这一步放在起中间件**之前**做。如果 Docker Desktop 用的不是 WSL2 引擎而是 Hyper\\\-V，`.wslconfig` 不生效，改在 Docker Desktop → Settings → Resources 里填同样的数字。

2. 检查：

```PowerShell
"Docker VM: {0} CPU, {1} GB" -f (docker info --format '{{.NCPU}}'), ([math]::Round([double](docker info --format '{{.MemTotal}}')/1GB,1))
Get-CimInstance Win32_ComputerSystem | Select-Object NumberOfLogicalProcessors, @{n='TotalMemGB';e={[math]::Round($_.TotalPhysicalMemory/1GB,1)}}
docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.CPUPerc}}"
```

**确认：**第一行 `Docker VM: 6 CPU, 7.x GB`（`memory=8GB` 减去内核占用，不会正好 8）；`docker stats` 里每个容器 `MEM USAGE / LIMIT` 的 LIMIT 是 compose 里写的 `mem_limit`（mysql 1\.5GiB、redis 512MiB、nacos 1GiB、kafka 1GiB、canal\-server 1\.5GiB），而不是整个 VM 的大小。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=N2Y1ZGVhNGYxYWJjZjYxMjNkZGVhOGY0NTU1Mzg1ZjRfMDI0NTJmNzM3MDA3NTYwMDU0MGY3YTc4YTk1NTU5NWFfSUQ6NzY4NjE2ODg3MzUwNjMyNzc3N18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.3 MySQL `lower_case_table_names` 与库名

**为什么**：JDBC URL 用的库名是 `InfiniteChat`（大写 I、C）。Linux 容器默认 `lower_case_table_names=0`，库名大小写敏感，库必须恰好叫 `InfiniteChat`。

```PowerShell
Invoke-MySql "SHOW VARIABLES LIKE 'lower_case_table_names'; SHOW DATABASES LIKE 'InfiniteChat'; SELECT VERSION();"
```

**确认**：三行输出依次是 `lower_case_table_names 0`（或 `1`，`2`两者都可）、`InfiniteChat`、版本号（8\.x）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MmY4YWUyMWExZTMzYjUxYzJjNDc1YjI2YmYzOWE5YTFfZTFjMmJiNGI3ZGE1ZWFhOWZkMmYyMDZlYjljMmY0ZmFfSUQ6NzY4NTgwNDc4OTY4NjQyMjQ1Nl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.4 Redis / Kafka / Nacos / Canal 可达

```PowerShell
Invoke-Redis PING
Invoke-Redis CONFIG GET databases
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --list"
(Invoke-WebRequest "http://localhost:$NACOS_HOST_PORT/nacos/v1/console/health/readiness" -UseBasicParsing).StatusCode
Test-NetConnection localhost -Port $CANAL_HOST_PORT | Select-Object TcpTestSucceeded
```

**确认**：`PONG`；databases ≥ 1；topic 列表能打印（首次启动服务前可能为空，不报错即可）；Nacos 返回 `200`；Canal `TcpTestSucceeded : True`。若 `kafka-topics` 报连接错误，改 `perf-env.ps1` 里的 `$KAFKA_BOOT_IN` 为容器内监听地址。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NzAzYjZjNThhY2E3YzMzZWY0MTc2OTBiZTMwMmI2ZmVfNjQwMjdjZDVkNGE0YzZjNGYzYzA2MTU3MzI5MmFiM2NfSUQ6NzY4NTgwNjU2MDYzMDU5MDQwNl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.5 用 perf profile 启动 4 个 Java 服务（`java -jar` \\\+ 限制堆）

**为什么**：本轮两个接口都不经网关，需要 UserService 8104，造数时被 Feign 调用、OfflineDataService 8101、RealTimeService 8102/9101、RedPacketService 8103。perf profile 下日志只写文件、握手用 `X-User-Id`、指标端点开放。

另外必须**显式限堆**：不写 `-Xmx` 时 JVM 默认最大堆 = 物理内存的 1/4，32G 机器上每个服务都能涨到 8 GB，4 个服务加 IDEA 自己的 2～4 GB 就把内存吃穿了。所以每个服务都带下面这组参数，并且推荐打包后用 `java -jar` 起，不在 IDEA 里跑（省掉 IDEA 和它挂的 agent，压测数据也更干净）：

```Plain Text
-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Dspring.profiles.active=perf
```

RealTimeService 要扛 2000 条 WebSocket 连接，单独给 `-Xmx1536m`。

#### **方式一（推荐）：打包后用 ****`java -jar`**** 启动**

源码下载：（已打包）

\[InfiniteChat\.zip\]

1. 仓库根目录打包：

```PowerShell
Set-Location <仓库根目录>
mvn -DskipTests package
```

2. 写 `start-services.ps1`（`-Repo` 改成仓库根目录；jar 按 `<模块名>\target\*.jar` 找，模块目录名不是这 4 个的话改 `n=`）：

```PowerShell
Set-Location C:\perf\infinitechat
@'
param([string]$Repo = "D:\Project\InfiniteChat")   # 改成仓库根目录
$common = "-XX:MaxMetaspaceSize=256m -XX:+UseG1GC -Dspring.profiles.active=perf -Dfile.encoding=UTF-8"
$svcs = @(
  @{ n="UserService";        heap="-Xms512m -Xmx1g"    },
  @{ n="OfflineDataService"; heap="-Xms512m -Xmx1g"    },
  @{ n="RedPacketService";   heap="-Xms512m -Xmx1g"    },
  @{ n="RealTimeService";    heap="-Xms512m -Xmx1536m" }   # 2000 条 WebSocket 连接，多给一点
)
foreach ($s in $svcs) {
  $jar = Get-ChildItem "$Repo\$($s.n)\target\*.jar" -ErrorAction SilentlyContinue |
         Where-Object Name -notmatch 'original|sources' | Select-Object -First 1
  if (-not $jar) { Write-Warning "找不到 $($s.n) 的 jar（$Repo\$($s.n)\target），先 mvn package 或改模块目录名"; continue }
  # 每个服务一个独立 cmd 窗口，窗口标题 = 服务名；/k 保证进程退出后窗口不关，启动报错看得到
  Start-Process cmd -WorkingDirectory $Repo -ArgumentList "/k title $($s.n) && java $($s.heap) $common -jar `"$($jar.FullName)`""
  Start-Sleep -Seconds 2
}
'@ | Set-Content -Encoding UTF8 .\start-services.ps1

.\start-services.ps1 -Repo "D:\Project\InfiniteChat"
```

工作目录仍是仓库根目录，所以日志照旧写在 `logs/<模块名>.log`（`logback-spring.xml`，根级别 WARN），本文其它步骤不用改。停服务：到对应窗口按 Ctrl\\\+C。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NDEzMGYzNzA1YzQ2ZDdiYzdjZjc2YzY1NWNlMjJhMTdfYmU5NzdmYTRkODQ2YzAyYmFlNWE4YjYzZjQ3ZWU1YzFfSUQ6NzY4NjE5MTE2NTcwNzcwMTIzNl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

> 执行后可能遇到的两个问题
> 
> **脚本被禁止运行。** 如果看到“因为在此系统上禁止运行脚本”，先执行下面这行再运行。它只对当前窗口生效：
> 
> ```PowerShell
> Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
> ```
> 
> 

3. 核对堆上限真的生效了：

```PowerShell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Select-Object ProcessId,
    @{n='Xmx';e={[regex]::Match($_.CommandLine,'-Xmx\S+').Value}},
    @{n='jar';e={[regex]::Match($_.CommandLine,'[^\\]+\.jar').Value}},
    @{n='RSS_MB';e={[int]($_.WorkingSetSize/1MB)}}
```

**确认**：4 行 `java.exe`，`Xmx` 列分别是 `-Xmx1g` × 3、`-Xmx1536m` × 1，`jar` 列是 4 个不同的模块；再做 1\\\.6。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=OTkwNTMzZDU1MTZmZGVjNmRmZDg4M2IxMTg2ZDIxOTRfYzk1OTAzMDY0MjBiY2EzYzIyMGQ0OWYyMzgyNjY5ZTdfSUQ6NzY4NjE5MTc1ODA0OTg4OTIyOF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)



#### **方式二（备选）：在 IDEA 里跑**

只在必须调试时用。依次编辑 IDEA 里四个服务的启动配置：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NThlODRjODZhYWZiMmUxMDJiYTZlYjhhYThmYTNkOGJfYjdjZWZhYmUwN2M5Zjg2MWQ0NjdkY2UzYTJhNWUxMTBfSUQ6NzY4NjE3MTg3NTk3MzU2NTM5NF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NGU1YTBlZjE5MjUyMWM0Mjk2YjgzNjY3ZGExOTBiZGZfYThiZjVmOGY3NDA5MmJhYzFiOTU0N2U0ZGRkNTYyMTNfSUQ6NzY4NjE3MTg3MTk0NzI0NjU3M18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

- Active profiles 填：

    ```Plain Text
    perf
    ```

- VM options 填（RealTimeService 把 `-Xmx1g` 换成 `-Xmx1536m`）：

```PowerShell
-Xms512m -Xmx1g -XX:MaxMetaspaceSize=256m -XX:+UseG1GC
```

- 用 **Run** 不要用 Debug（debug agent 会拖慢并改变数字）；关掉其它打开的项目；Settings → Build → Compiler 里取消 "Build project automatically"，避免压测中触发增量编译。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZDc1YTg4MjBiNmM3NGM3YzQ5ZDNmMzdkMWNlMTNmZDVfZWNkYWYwODU5MzZlYjEyZGI3YzgyYWY3ZjQ3MDY3NjhfSUQ6NzY4NjE3MTk4MTcxMjEwMDU1Ml8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

    

日志写在启动时的工作目录下 `logs/<模块名>.log`（IDEA 默认为项目根目录，即仓库里已有的 `logs/` 文件夹；`logback-spring.xml`，根级别 WARN）。

### 1\.6 health 端点

```PowerShell
foreach ($p in $P_OFFLINE,$P_REALTIME,$P_REDPACKET,$P_USER) {
  try { $h = Invoke-RestMethod "http://localhost:$p/actuator/health"; "{0}: {1}" -f $p, $h.status }
  catch { "{0}: DOWN ({1})" -f $p, $_.Exception.Message }
}
```

**确认**：四行都是 `UP`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MTc1ZWFhMDI3MjE5MTA0MzlmNWNiZDE5OWE0N2NlOTdfNzAyYzIxMGUyNmUzOTM5ZmU1MjVhYmFhMThjMzhjNzVfSUQ6NzY4NTgwODYyOTg2NTMwMzAyN18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.7 自定义指标已注册

**为什么**：压测期间全靠这些指标判断异步链路是否跟得上，指标缺失说明没用 perf profile 启动或包没编译进去。

```PowerShell
$checks = @(
  @{p=$P_REALTIME;  m="im_channel_online"},
  @{p=$P_REALTIME;  m='im_push_total{application="RealTimeService",result="ok"}'},
  @{p=$P_REALTIME;  m="im_push_kafka_delay_seconds_count"},
  @{p=$P_OFFLINE;   m="im_store_kafka_delay_seconds_count"},
  @{p=$P_REDPACKET; m="redpacket_receive_kafka_delay_seconds_count"},
  @{p=$P_REDPACKET; m='hikaricp_connections_active{application="RedPacketService",pool="RedPacketService-hikari"}'},
  @{p=$P_OFFLINE;   m='hikaricp_connections_active{application="OfflineDataService",pool="OfflineDataService-hikari"}'}
)
foreach ($c in $checks) {
  $body = (Invoke-WebRequest "http://localhost:$($c.p)/actuator/prometheus" -UseBasicParsing).Content
  "{0} {1,-90} {2}" -f $c.p, $c.m, ($(if ($body.Contains($c.m)) {"OK"} else {"MISSING"}))
}
```

**确认**：7 行全是 `OK`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZjFlYjJkN2JhYjFkMWQ5ZDNkZDFlNzU5ZDIzYWY4MGVfNjg3OTIyMzRhNzQ1YmIyMTdlZTgyZGUwNGUwYzI0YTdfSUQ6NzY4NTgwODk4Mzg3MzAzMTE0MV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 1\.8 Kafka 主链路 topic 分区数

**为什么**：`push-topic`/`store-topic` 由 `MessageTopicConfig` 按 `im.topic.partitions=6` 声明；但 `NewTopic` 只在 topic 不存在时创建，老环境里残留的 1 分区 topic 不会被自动改。

```PowerShell
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --describe --topic 'push-topic|store-topic|push-topic\.DLT|store-topic\.DLT|topic-redpacket-receive|topic-redpacket-receive\.DLT|topic-redpacket-completed|redpacket-creation-topic'" | Select-String "PartitionCount"
```

**确认**：应输出 8 行；少了哪一行，说明对应的 topic 不存在。`push-topic`、`store-topic` 及其 `.DLT` 都是 `PartitionCount: 6`；

`topic-redpacket-receive`、其 `.DLT`、`topic-redpacket-completed`、`redpacket-creation-topic` 都是 `PartitionCount: 3`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MWJhZWI5YjVkYzY3YmFjZjEyZDk5MmVkZTc3OGM3MjZfMjFiN2Q0YjhjOGI4ODQzOTcwMjVlMTk2OTYyOTg3ZWJfSUQ6NzY4NjE5MjA2Nzk5NDg0ODQ1NV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

若 push/store 是 1，执行：

```PowerShell
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --alter --topic push-topic --partitions 6"
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --alter --topic store-topic --partitions 6"
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --alter --topic push-topic.DLT --partitions 6"
Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --alter --topic store-topic.DLT --partitions 6"
```

然后重启 RealTimeService 与 OfflineDataService 让消费者重新分配分区。

### 1\.9 JMeter 与 WebSocket 插件

```PowerShell
& "$JMETER_HOME\bin\jmeter.bat" --version 2>$null | Select-String "5\."
Get-ChildItem "$JMETER_HOME\lib\ext" | Where-Object Name -match 'websocket.*samplers'
```

**确认**：第一条：版本 5\.x；第二条列出 `jmeter-websocket-samplers-1.3.x.jar`（旧版本名为 `JMeterWebSocketSamplers-1.2.x.jar`，两种都可以）。没有则打开 JMeter GUI → Options → Plugins Manager → Available Plugins → 勾选 *WebSocket Samplers by Peter Doornbosch* → Apply Changes and Restart JMeter。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MGI0MTAwYThiMmM3ZDMyNWUxNmIyNDAzYTBhOTFmNDlfZTlkODMyZmI4NmU3M2EyYjZmMzAzYzQzMjQ1ZTFhMWVfSUQ6NzY4NjE5NTAzNDQ2MjM5MTUxNl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

## 数据准备

### 2\.1 发送消息场景：不建任何表行

**为什么**：这条链路从头到尾不查库。握手在 perf 下只读 `X-User-Id` 头（`WebSocketAuthHeader`）；`WebSocketHandel` 把 JSON 补上雪花 ID 后直接双写 Kafka；push 消费者按 `senderId`/`receiverId` 在进程内 `ChannelManager` 里找连接；store 消费者把消息原样 `INSERT message`，表上没有外键。所以 userId / sessionId 用编号就行。

**配对规则**（JMeter 里用 JSR223 按线程号算，见 章节3\.2）：

|**第 i 个线程（i 从 1 起）**|**值**|
|---|---|
|发送者 senderId|200000 \+ i|
|接收者 receiverId|300000 \+ i|
|会话 sessionId|400000 \+ i|

- 发送者线程 i 与接收者线程 i 一一配对，两个线程组线程数必须相等。

- `push-topic` 以 `sessionId` 字符串为 key 分区（murmur2 % 6）。sessionId 数量 = 发送者线程数；≥ 30 个不同 sessionId 时 6 个分区实际上必然全部覆盖。最低档并发就用 100 线程，覆盖没有问题；章节7\.1 有验证方法。

### 2\.2 领取红包场景：建 user / user\_balance / session / user\_session

**为什么**：领取本身只走 Redis Lua，但红包必须通过真实 `/send` 接口生成，`/send` 会 Feign 调 UserService 查发送者 `user.state`、查 `user_session` 判断是否群成员、拉群成员列表写进 `redpacket:{id}:allow`。单聊红包还要查 `friend` 表，所以本轮用**群聊红包**（sessionType=1），只需这四张表。

数据规划：

|**项**|**值**|**原因**|
|---|---|---|
|群会话 sessionId|900001|与消息场景的 40xxxx 不重叠|
|发送者 userId|100000|群主，role=0|
|领取者 userId|100001 ～ 100500|500 个普通成员；getUserIdBySessionId 拿到的快照就是这 501 人|
|每个红包|普通红包，500 份，总额 5\.00 元|每份恰好 1 分，500 份 = 500 个领取者各领一次；totalCount ≤ 群成员数 校验通过|
|红包个数 PacketCount|6000（可调）|6000 × 500 = 300 万条 \(userId, redPacketId\)，够 章节6 全部阶梯|
|发送者初始余额|1,000,000,000 分|6000 × 500 分 = 300 万分，远够|

每一对 `(userId, redPacketId)` 只出现一次，且每个红包份数 = 领取者数，所以 CSV 里每一行都应该返回 `恭喜您，领取成功`，三层断言（章节3\.1）对所有行成立。

#### 2\.2\.1 建表 SQL（写成文件，再灌进去）

```PowerShell
. .\perf-env.ps1
@'
-- perf seed: 1 group session, 1 sender (100000), 500 receivers (100001..100500)
SET NAMES utf8mb4;
DELETE FROM user_session WHERE session_id = 900001;
DELETE FROM session       WHERE session_id = 900001;
DELETE FROM user_balance  WHERE user_id BETWEEN 100000 AND 100500;
DELETE FROM `user`        WHERE user_id BETWEEN 100000 AND 100500;

INSERT INTO session (session_id, name, type, status) VALUES (900001, 'perf-group', 1, 0);

-- 501 users via a numbers table built from cross join
INSERT INTO `user` (user_id, password, nickname, email, gender, state, role, is_delete)
SELECT 100000 + n, 'perf', CONCAT('perf_', 100000 + n), CONCAT('perf_', 100000 + n, '@perf.test'), 2, 0, 0, 0
FROM (
  SELECT a.n + b.n*10 + c.n*100 AS n
  FROM (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,
       (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c
) t WHERE n <= 500;

INSERT INTO user_balance (user_id, balance)
SELECT user_id, IF(user_id = 100000, 1000000000, 0) FROM `user` WHERE user_id BETWEEN 100000 AND 100500;

INSERT INTO user_session (user_id, session_id, role, status)
SELECT user_id, 900001, IF(user_id = 100000, 0, 2), 0 FROM `user` WHERE user_id BETWEEN 100000 AND 100500;
'@ | Set-Content -Encoding ASCII .\seed_redpacket.sql
Invoke-MySqlFile .\seed_redpacket.sql
Invoke-MySql "SELECT (SELECT COUNT(*) FROM user WHERE user_id BETWEEN 100000 AND 100500) users, (SELECT COUNT(*) FROM user_balance WHERE user_id BETWEEN 100000 AND 100500) balances, (SELECT COUNT(*) FROM user_session WHERE session_id=900001) members, (SELECT balance FROM user_balance WHERE user_id=100000) sender_balance;"
```

**确认**：输出一行 `501  501  501  1000000000`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NjA1MTU0NDA4Yzc0ZTFjZDQ3NTMxZmYxYjNiNWFhZmVfYzJhODk2MGI5OWExMWViYzBhZTUwMGQ3ZGUyOGE4ZDRfSUQ6NzY4NjE5NjUwMDc0NDkzMjMxNl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 2\.2\.2 清掉 UserService 的群成员缓存

**为什么**：`getUserIdBySessionId` 把成员列表缓存在 Redis `session:members:{sessionId}`（TTL 600～720 s）。如果之前调过，`/send` 会拿到旧快照写进 `allow` 集合，领取全部 `-4`。

```PowerShell
Invoke-Redis DEL session:members:900001
Invoke-Redis EXISTS session:members:900001
```

**确认**：第二条输出 `0`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZDE5OTQzOTQyOTlmYWMzYzBkYjUyOGJjZWNjZmI3NWJfMGM0MzE0MmVlMWM5OTI4N2FlNTg1Mzg4M2MxZDliZjFfSUQ6NzY4NjE5NjU3MTIxMzQ2NjYwMl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 2\.2\.3 通过真实 `/send` 接口预生成红包

**为什么**：只有走 `/send`，`red_packet` 行、发送者扣款、`balance_log`、Redis 的 `grant/allow/pool`、过期 ZSet 才会全部到位。`/send` 挂了 `@PreventDuplicateSubmit(expireSeconds=3)`，防重 key = `prevent:duplicate:sendRedPacket:{全部入参 JSON 的 hashCode}`，所以每次请求 `clientMessageId` 必须不同。红包 24 小时过期（Redis key 25 小时 TTL）：**造数后 24 小时内必须压完**，记录下面打印的截止时间。

```PowerShell
. .\perf-env.ps1
$PacketCount = 6000            # 需要更多行就改这里；每个红包 500 行
$url  = "http://localhost:$P_REDPACKET/api/chat/redPacket/send"
$out  = ".\redpacket_ids.txt"
if (Test-Path $out) { Remove-Item $out }
$sw = [Diagnostics.Stopwatch]::StartNew()
$fail = 0
for ($i = 1; $i -le $PacketCount; $i++) {
  $body = @{
    sessionId = 900001; senderId = 100000; receiverId = $null
    type = 3; sessionType = 1
    clientMessageId = "perf-rp-" + [guid]::NewGuid().ToString()
    body = @{ redPacketType = 0; totalAmount = 5.00; totalCount = 500; redPacketWrapperText = "perf" }
  } | ConvertTo-Json -Compress
  try {
    $r = Invoke-RestMethod -Uri $url -Method Post -Body $body -ContentType "application/json; charset=utf-8" -TimeoutSec 10
    if ($r.code -eq 200) { Add-Content $out $r.data.redPacketId.ToString() }
    else { $fail++; Write-Warning "#$i code=$($r.code) msg=$($r.message)" }
  } catch { $fail++; Write-Warning "#$i $($_.Exception.Message)" }
  if ($i % 500 -eq 0) { "{0}/{1}  {2:N0}s" -f $i, $PacketCount, $sw.Elapsed.TotalSeconds }
}
"done: ok={0} fail={1} in {2:N0}s" -f ((Get-Content $out).Count), $fail, $sw.Elapsed.TotalSeconds
```

> 执行脚本需要一段时间，耐心等待，预估3分钟左右
> 
> 

**确认**：`ok=6000 fail=0`；`redpacket_ids.txt` 有 6000 行 19 位数字。任何 `code≠200` 都要先查原因（常见：`91008 服务暂时不可用` = UserService 没起来或没注册到 Nacos；`50001 余额不足` = 2\.2\.1 没跑；`92001 红包数量不能超过群成员数量` = 2\.2\.2 没清缓存）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=M2ZhMzY4MDZiOGQ5YzAwMGVkYzM0M2I3ZWY0ZGNiYjZfMzFkNDRiMTE0MzIzNzUzZTI3YWEyZjE1NDQ4YmUxNDZfSUQ6NzY4NjE5OTI4OTM0MDYyODE2M18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 2\.2\.4 生成 JMeter 用的 CSV（按阶段拆成多个文件）

**为什么**：JMeter 每次启动都从 CSV 第一行读起。同一份 CSV 跑第二档就会重复领取（返回「您已经领取过该红包了」，HTTP 仍是 200 但走的不是首次领取路径，这一档作废）。所以**一档一个文件，文件名就叫档名**，第 6 章的 `run-stage.ps1` 按档名自己找文件，你不用手工指定路径，也就不会用错。

**每档要多少行**：`/receive` 同步路径只走 Redis Lua，50 线程不限速就有 **≈10,500 TPS**，30 万行 CSV **28 秒**就被读完，`Stop thread on EOF` 触发、JMeter 提前结束，那一档直接作废。所以行数不能按「占总量的百分比」切，必须按 **目标 TPS × 时长 × 1\.5 余量** 算。另外单独留一份 `rp_spare.csv` 作备用池，补档或某一档作废重跑时从里面剪（章节6\.2、6\.5）。

把下面这段存成 `make-csv.ps1`（附件里有现成的），执行一次：

```PowerShell
Set-Location C:\perf\infinitechat
.\make-csv.ps1
```

脚本做的事：把 6000 个红包 × 500 个领取者铺成 300 万行，**外层循环用户、内层循环红包**，这样相邻两行是不同红包，Redis 热点分散；然后按下表切成 9 个文件。

|**文件名**|**行数**|**给哪一档用**|
|---|---|---|
|`rp_warmup.csv`|12,000|`rp_warmup` 预热（100/s × 60 s）|
|`rp_tps100.csv`|30,000|`rp_tps100`（100/s × 180 s）|
|`rp_tps200.csv`|60,000|`rp_tps200`|
|`rp_tps400.csv`|110,000|`rp_tps400`|
|`rp_tps800.csv`|220,000|`rp_tps800`|
|`rp_spare.csv`|250,000|**备用池**，补档 / 重跑时用 `new-csv-from-spare.ps1` 从这里剪|
|`rp_open50.csv`|700,000|`rp_open50`（不限速 60 s，≈10\.5k TPS）|
|`rp_open200.csv`|800,000|`rp_open200`|
|`rp_open400.csv`|剩余 ≈818,000|`rp_open400`|

**确认**（PowerShell 写 300 万行约 1～3 分钟）：打印 9 行文件名 \+ 行数，最后一行 `合计 3,000,000 行（应等于 3,000,000）`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZGI5NDRmMmVkZWRiZGM4MmFiMzAxOGFjNzA1ZWRhOGJfYjlkNzAyMjk3YzczNTQwYjdkMTU4YjYyYWI3NTdlMWJfSUQ6NzY4NjU5NDA4OTY0MjM4MDI0OV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

> 每一对 `(userId, redPacketId)` 在这 300 万行里只出现一次，而且每个红包的份数（500）正好等于领取者人数，所以**理论上每一行都会返回「恭喜您，领取成功」**，章节3\.1 的三层断言对所有行都成立。压测时只要出现「已经领取过」或「已被领完」，一定是文件被用了第二次或者数据没清干净，不是系统的问题。
> 
> 开环档（`rp_open*`）行用完提前结束是正常的，只要跑满 30 秒这一档就有效。
> 
> 



#### 2\.2\.5 造数后校验（Redis 与 MySQL 两侧）

**为什么**：领取路径完全信任 Redis 里的 `grant`、`allow`、`pool` 三个 key；任何一个缺了，压测里全是失败样本。

```PowerShell
cd C:\perf\infinitechat
. .\perf-env.ps1
$ids = Get-Content .\redpacket_ids.txt
$n = $ids.Count
# --- Redis：用管道一次性查 6000 个红包，不逐条 docker exec ---
$cmds = foreach ($id in $ids) { "LLEN redpacket:${id}:pool"; "SCARD redpacket:${id}:allow"; "HGET redpacket:${id}:grant sessionType"; "TTL redpacket:${id}:pool" }
$res = $cmds | docker exec -i $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0
$pool  = @(); $allow = @(); $grant = @(); $ttl = @()
for ($i = 0; $i -lt $res.Count; $i += 4) { $pool += [int]$res[$i]; $allow += [int]$res[$i+1]; $grant += $res[$i+2]; $ttl += [int]$res[$i+3] }
"pool=500  : {0}/{1}" -f (($pool  | Where-Object { $_ -eq 500 }).Count), $n
"allow=501 : {0}/{1}" -f (($allow | Where-Object { $_ -eq 501 }).Count), $n
"grant=1   : {0}/{1}" -f (($grant | Where-Object { $_ -eq '1' }).Count), $n
"ttl>24h   : {0}/{1}" -f (($ttl   | Where-Object { $_ -gt 86400 }).Count), $n
"expire zset: " + (Invoke-Redis ZCARD redpacket-expire-zset)
# --- MySQL ---
Invoke-MySql "SELECT COUNT(*) packets, SUM(status=0) status0, SUM(total_amount) total_fen, SUM(total_count) total_shares FROM red_packet WHERE session_id=900001;"
Invoke-MySql "SELECT balance FROM user_balance WHERE user_id=100000;"
Invoke-MySql "SELECT type, COUNT(*), SUM(amount) FROM balance_log WHERE user_id=100000 GROUP BY type;"
Invoke-MySql "SELECT COUNT(*) FROM message WHERE session_id=900001 AND type=3;"
```

**确认**（PacketCount=6000 时）：

|**检查**|**预期**|
|---|---|
|pool=500 / allow=501 / grant=1 / ttl\>24h|都是 6000/6000|
|redpacket\-expire\-zset|6000|
|red\_packet|6000 6000 3000000 3000000|
|发送者余额|997000000（10 亿 − 300 万分）|
|balance\_log type=0|0 6000 \-3000000|
|message type=3|6000（store 消费者正常，可能延迟几秒）|

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=YWM0ZjlkOWM5ZTkwMTk2ZTUwODk4NzUyOGUwY2Y4MGFfMmU2NTVlMzIyNzU1NjVhZTg4MmZlNjhhMGY0ODVhNTFfSUQ6NzY4NjIwMTIzNTc0MDg3MTYzNF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 2\.3 重跑前的清理与重新造数（第二次及以后压测）

这一节是给「已经压过一轮、要重新压」的情况用的。**第一次压测、目录还是空的，跳过本节直接看 章节3。**

> #### 2\.3\.1 为什么上一轮的数据一定要全部清掉
> 
> 不是「建议清」，是「不清就没法用」。三个原因，任何一个都足以让新一轮数据作废：
> 
> 1. **每一对 \(领取者, 红包\) 只能领一次。** /yy上一轮 `rp_50` 那一档读掉了 `rp_stage1.csv` 的 30 万行，这 30 万对已经领过了。同一批红包再压，接口会返回「您已经领取过该红包了」——HTTP 还是 200，JMeter 那层断言会失败，而且走的根本不是首次领取的代码路径，测出来的响应时间没有意义。
> 
> 2. **红包 24 小时过期。** 上一轮的红包是 2026\-09\-17 凌晨 2 点左右造的，Redis key 的 TTL 是 25 小时，也就是 9 月 18 日凌晨就到期了。过期之后 `ExpirationDispatcher` 会把没领完的钱退给发送者，在 `balance_log` 里写一堆 `type=2` 的退款记录——而 章节7\.3 的账务校验明确要求 `refund_rows = 0`。只要过了期，这批数据永远对不平。
> 
> 3. **上一轮的结果文件会和新一轮混在一起。** 上一轮的档名是 `rp_50`、`msg_100`，新一轮是 `rp_tps100`、`rp_open50`…… 混在同一个 `results\` 目录里，很容易把上一轮的 `metrics_rp_100.csv` 当成本轮的数据来读（上一轮已经出过一次「采集脚本 Tag 写错、文件名对不上档位」的事）。
> 
> **清理不动的东西**：4 个 Java 服务、5 个中间件容器、Kafka 里的历史消息和死信队列（第 7 章全部用前后差值对账，历史数据不影响）、`perf-env.ps1` 和两个 `.jmx`。
> 
> 

#### 2\.3\.2 第 0 步：先体检，看清楚现在到底是什么状态

清之前先看一眼，心里有数，也方便出问题时回溯。把 `check-perf-data.ps1` 放进 `C:\perf\infinitechat\`，然后：

```PowerShell
Set-Location C:\perf\infinitechat
.\check-perf-data.ps1
```

它是**只读的**，不会改任何东西。输出分 6 段，重点看这几行：

|**输出里的行**|**正常情况**|**看到什么说明有问题**|
|---|---|---|
|其中退款 type=2|0|大于 0 = 上一批红包已经过期退款了，这批数据必须全清，没有别的选择|
|第一个红包在 Redis 里|显示「剩余 xx 分钟」|显示「已不存在（TTL=\-2）」= 红包已经从 Redis 里消失，MySQL 里是一堆孤儿数据|
|领取记录\(按红包\)|上一轮领了多少就是多少|上一轮 rp\_50 之后应该是 30 万左右|
|rp\_stage1\.csv 后面的备注|—|这一栏会告诉你哪些 CSV 已经被用过|
|4 个消费组的 lag|**必须全是 0**|不是 0 就先别清（见下一步）|

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=YWMzYjhlNzZhMjhiZWMxYTQwMDVjNjgyM2Y2ZTYyM2RfMzFhNGEyOTAwZWEzNjJmZDRlNWFkYjA1OGIxNTc0NjVfSUQ6NzY4NjU4NDgzOTQwNjg5ODE1Nl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 2\.3\.3 第 1 步：确认后台没有在跑的事件

如果上一步看到 lag 不是 0，说明还有事件正在往 MySQL 写。这时候清库，清完之后消费者又会把数据写回来，越清越乱。先等它归零：

```PowerShell
.\wait-lag-zero.ps1
```

它每 5 秒打印一行，全部归零并稳定 15 秒后自己退出。上一轮如果是在 30 万积压的状态下中断的，这里要等 20 分钟左右，耐心等完。

#### 2\.3\.4 第 2 步：执行清理

把 `reset-perf-data.ps1` 放进目录，先**空跑一次**看它要删什么（不加参数时它只打印清单，什么都不做）：

```PowerShell
.\reset-perf-data.ps1
```

确认清单没问题，再加 `-Yes` 真正执行：

```PowerShell
.\reset-perf-data.ps1 -Yes
```

它按顺序做 6 件事：

|**步**|**做什么**|**为什么**|
|---|---|---|
|1|再查一次 lag，不是 0 就直接报错退出|防止边清边写|
|2|清 MySQL：session\_id=900001 的红包 / 领取记录 / 消息，user\_id 100000\~100500 的用户 / 余额 / 流水 / 群成员，session\_id 400001\~410000 的单聊消息|把两个场景的痕迹全部抹掉，包括孤儿的领取记录|
|3|清 Redis：redpacket:\* 的全部 key（用 SCAN 扫，不依赖 redpacket\_ids\.txt）、redpacket\-expire\-zset、session:members:900001|即使 redpacket\_ids\.txt 丢了也能清干净；群成员缓存不清，重新造数时 allow 名单会是旧快照|
|4|删掉 rp\_\*\.csv、\*\.used 标记、redpacket\_ids\.txt、seed\_redpacket\.sql|旧 CSV 的行数和文件名都和新第 6 章对不上，留着只会认错|
|5|把 results 目录改名成 results\_old\_\<月日\_时分\>|不是删除，上一轮的 jtl / 报告都还在，只是挪开不和新数据混|
|6|复核：MySQL 残留行数和 Redis 残留 key 都应该是 0|—|

**确认**：最后一行是绿色的「清理完成」，且倒数第二行 `MySQL 残留行数 = 0；Redis 残留 key = 0`。

#### 2\.3\.5 第 3 步：重启 4 个 Java 服务

清完数据**建议重启**（不是必须，但强烈建议）。理由：服务里的 Micrometer 计数器是进程启动以来的累计值，上一轮跑完 `im_push_total{result="miss"}` 的绝对值已经到了 300 万。重启一次全部归零，后面看快照、看采集文件时不容易看花眼。

做法就是 章节1\.5：到 4 个 cmd 窗口里 `Ctrl+C`，再执行一次 `.\start-services.ps1 -Repo "<仓库根目录>"`。

重启后按顺序确认三件事（都是原文照做）：

```PowerShell
. .\perf-env.ps1
# 1.6 health 端点：4 行都要 UP
foreach ($p in $P_OFFLINE,$P_REALTIME,$P_REDPACKET,$P_USER) {
  try { $h = Invoke-RestMethod "http://localhost:$p/actuator/health"; "{0}: {1}" -f $p, $h.status }
  catch { "{0}: DOWN ({1})" -f $p, $_.Exception.Message }
}
```

再跑一次 章节1\.7（7 行全 `OK`）和 章节1\.8（分区数对）。这两项如果不过，后面所有采集和对账都是空的。

#### 2\.3\.6 第 4 步：重新造数（顺序一步都不能跳）

从这里开始就是 **章节2\.2** 的原始流程，按顺序执行：

|**顺序**|**做什么**|**在哪一节**|**确认**|
|---|---|---|---|
|1|建 501 个用户、余额、群、群成员|章节2\.2\.1|输出一行 501 501 501 1000000000|
|2|**清掉群成员缓存**|章节2\.2\.2|第二条输出 0|
|3|通过真实 /send 造 6000 个红包|章节2\.2\.3|ok=6000 fail=0，约 3 分钟|
|4|生成新版 CSV|见下|9 个文件，合计 300 万行|
|5|造数后校验|章节2\.2\.5|表里 6 项全部符合|

第 2 步最容易漏。`getUserIdBySessionId` 会把群成员列表缓存在 Redis 里 10～12 分钟，如果上一步刚删完用户又立刻造红包，`/send` 拿到的是旧快照，写进 `allow` 集合的是旧的 501 个用户 ID——压测时每一次领取都会返回「无权领取」。`reset-perf-data.ps1` 已经删过一次这个缓存，但 章节2\.2\.1 建完用户之后到造红包之前**如果有任何东西调用过群成员接口**，缓存会重新长出来，所以 章节2\.2\.2 必须再执行一次。

#### 2\.3\.7 第 5 步：造完再体检一次 \+ 冒烟

```PowerShell
.\check-perf-data.ps1
```

**这次期望看到的**（和 2\.3\.2 那次对照着看）：

|**项**|**期望值**|
|---|---|
|红包条数 red\_packet|6000|
|领取记录（两行）|0 / 0|
|balance\_log 条数 / 其中退款|6000 / 0|
|发送者余额|997000000|
|群成员 user\_session|501|
|redpacket:\* key 总数|30000（6000 × 5 个 key）|
|过期 ZSet 里的红包数|6000|
|群成员缓存是否存在|0|
|4 个消费组 lag|全 0|
|CSV 列表|9 个文件，没有任何一个带「已用过」标记|
|第一个红包剩余可用|≥ 20 小时，并记下截止时间|

最后跑一次 章节5 的 `smoke.ps1`，看到 `ALL GREEN - 可以开压` 才进第 6 章。

> **把截止时间写在便签上贴屏幕**。`check-perf-data.ps1` 会打印「截止 MM\-DD HH:mm」，那之后这批红包全部作废。按 章节6\.7 的时间预算，完整跑完约 4 小时，时间是够的，但不要跨夜。
> 
> 

## JMeter 脚本

两个脚本都用 `${__P(name,default)}` 读命令行属性，章节6 用 `-J` 传值。GUI 里 File → New，按下表逐项填，保存到 `C:\perf\infinitechat\`。

### 3\.1 领取红包 `redpacket_receive.jmx`

树结构：

```Plain Text
Test Plan
└─ Thread Group「receive」
   ├─ CSV Data Set Config
   ├─ HTTP Header Manager
   ├─ Constant Throughput Timer「tps」  
   └─ HTTP Request「POST /receive」
      ├─ Response Assertion「HTTP 200」
      ├─ JSON Assertion「$.code == 200」
      └─ Response Assertion「成功文案」
```

**Test Plan**（左键 Test Plan）：

|**字段**|**值**|
|---|---|
|Run Thread Groups consecutively|不勾|
|Functional Test Mode|不勾|

**Thread Group**（Add → Threads \(Users\) → Thread Group）：

|**字段**|**值**|
|---|---|
|Name|receive|
|Action to be taken after a Sampler error|Continue|
|Number of Threads \(users\)|$\{\_\_P\(threads,50\)\}|
|Ramp\-up period \(seconds\)|$\{\_\_P\(rampup,30\)\}|
|Loop Count|勾 Infinite|
|Same user on each iteration|勾|
|Delay Thread creation until needed|不勾|
|Specify Thread lifetime|勾|
|Duration \(seconds\)|$\{\_\_P\(duration,180\)\}|
|Startup delay \(seconds\)|0|

**CSV Data Set Config**（Add → Config Element → CSV Data Set Config）：

|**字段**|**值**|
|---|---|
|Filename|$\{\_\_P\(csv,C:/perf/infinitechat/rp\_stage1\.csv\)\}|
|File encoding|UTF\-8|
|Variable Names|userId,redPacketId|
|Ignore first line|**TRUE**|
|Delimiter|,|
|Allow quoted data?|FALSE|
|Recycle on EOF?|**FALSE**|
|Stop thread on EOF?|**TRUE**|
|Sharing mode|All threads|

**HTTP Header Manager**（Add → Config Element）：一行 `Content-Type` = `application/json`。

**Constant Throughput Timer「tps」**（Thread Group 右键 Add → Timer → Constant Throughput Timer，直接挂在线程组下，对组内所有采样器生效）：

|**字段**|**值**|
|---|---|
|Name|tps|
|Target throughput \(in samples per minute\)|\{\_\_jexl3\(\{\_\_P\(tps,100\)\} \* 60\)\}|
|Calculate Throughput based on|**all active threads in current thread group**|

> **为什么必须有这一层**：`/receive` 只走 Redis Lua，服务端平均 2 ms，50 线程开环在本机 loopback 上就是 ≈10,500 TPS；而领取事件的 Kafka 消费者（`redpacket-receive-handler-group`，3 分区 × 1 线程，每条一次 MySQL 事务）本机实测只有 **≈250 条/s**。不控速的话最低档就把异步链路打爆 40 倍，四档之间没有任何区分度，且每档都要等几十分钟 lag 归零。所以正式阶梯用 **固定 50 线程 \+ 目标 TPS** 逐级抬高，找异步链路跟得上的最高 TPS；同步路径的极限与 Tomcat 排队拐点另用「开环档」单独看（章节6\.2）。
> 
> **Target throughput 这一格的值**：`${__P(tps,100)}` 取命令行 `-Jtps` 的值（GUI 里直接打开、没有 `-J` 时取默认 100）；这个字段的单位是「每分钟样本数」，而阶梯按每秒定，所以外层用 `__jexl3` 乘 60。A 组 `-Jtps=100 / 200 / 400 / 800` 实际生效的分别是 6000 / 12000 / 24000 / 48000，预热档同为 6000。
> 
> **计算模式为什么不能选 \(shared\)**：JMeter 5\.6\.3 的两个 shared 模式会先把「相邻两次请求的间隔」`60000 ÷ 每分钟目标` 四舍五入成整毫秒再排队，只有能整除 1000 的 TPS 才准：100 → 10 ms、200 → 5 ms 没问题；400 → 2\.5 ms 取整成 3 ms，实际只有 ≈333 TPS；800 → 1\.25 ms 取整成 1 ms，实际 ≈1000 TPS。不带 shared 的模式是「当前活动线程数 × 间隔」再取整，50 线程时 400 TPS = 每线程 125 ms、800 TPS = 62\.5 → 63 ms（≈794 TPS），误差 \<1%；ramp\-up 期间按当时的活动线程数分摊，总速率同样贴近目标。
> 
> 开环档传 `-Jtps=100000`（每分钟 600 万；非 shared 模式下每线程间隔只有 1～4 ms，比单次请求耗时还短）即等于不限速；**不要传 0**，Constant Throughput Timer 目标为 0 时线程会永久等待。
> 
> 

**HTTP Request**（Add → Sampler → HTTP Request）：

|**字段**|**值**|
|---|---|
|Name|POST /receive|
|Protocol|http|
|Server Name or IP|localhost|
|Port Number|8103|
|HTTP Request method|POST|
|Path|/api/chat/redPacket/receive|
|Content encoding|UTF\-8|
|Use KeepAlive|**勾**|
|Redirect Automatically / Follow Redirects|都不勾|
|Use multipart/form\-data|不勾|
|Body Data（Tab 切到 Body Data）|\{"userId":$\{userId\},"redPacketId":$\{redPacketId\}\}|
|Advanced → Client implementation|HttpClient4|
|Advanced → Timeouts Connect / Response \(ms\)|3000 / 5000|

**Response Assertion「HTTP 200」**（HTTP Request 右键 Add → Assertions → Response Assertion）：

|**字段**|**值**|
|---|---|
|Name|HTTP 200|
|Apply to|Main sample only|
|Field to Test|**Response Code**|
|Pattern Matching Rules|**Equals**|
|Patterns to Test|**200**|

**JSON Assertion「$\.code == 200」**（Add → Assertions → JSON Assertion）：

|**字段**|**值**|
|---|---|
|Assert JSON Path exists|$\.code|
|Additionally assert value|勾|
|Match as regular expression|不勾|
|Expected Value|200|
|Expect null / Invert assertion|都不勾|

> 业务错误（余额不足、无权领取等）走 `GlobalExceptionHandler`，HTTP 仍是 200，只有 `code` 变，所以第二层必须有。
> 
> 

**Response Assertion「成功文案」**（HTTP Request 右键 Add → Assertions → Response Assertion）：

|**字段**|**值**|
|---|---|
|Name|成功文案|
|Field to Test|Text Response|
|Pattern Matching Rules|Contains|
|Patterns to Test|恭喜您，领取成功|

说明：这一层区分「首次领取成功」与「已领取过 / 已领完」——后两者 code 也是 200，但不写 Kafka。

**两个必须传给 JMeter 的属性**（章节6 命令里已带，这里说明原因）：

- `-Jsampleresult.default.encoding=UTF-8`：Spring 返回的 `Content-Type: application/json` 不带 charset，JMeter 默认按 ISO\-8859\-1 解码，中文断言会失败。

- `-Jhttpclient.reset_state_on_thread_group_iteration=false`：否则每次线程组迭代都关连接重建，KeepAlive 形同虚设。

### 3\.2 发送消息 `message_send.jmx`

树结构：

```Plain Text
Test Plan
├─ Thread Group「receivers」          （先启动，只读不写）
│  ├─ Once Only Controller
│  │  └─ WebSocket Open Connection
│  │     ├─ JSR223 PreProcessor「pair-id」
│  │     └─ HTTP Header Manager（X-User-Id）
│  └─ WebSocket Single Read Sampler「recv」
└─ Thread Group「senders」            （延迟 20 s 启动）
   ├─ Once Only Controller
   │  └─ WebSocket Open Connection
   │     ├─ JSR223 PreProcessor「pair-id」
   │     └─ HTTP Header Manager（X-User-Id）
   └─ WebSocket request-response Sampler「send」
      ├─ JSR223 PreProcessor「cmid」
      ├─ Constant Timer
      └─ Response Assertion「echo clientMessageId」
```

**Thread Group「receivers」**：

|**字段**|**值**|
|---|---|
|Number of Threads|$\{\_\_P\(threads,100\)\}|
|Ramp\-up period|$\{\_\_P\(rampup,30\)\}|
|Loop Count|Infinite|
|Specify Thread lifetime|勾|
|Duration \(seconds\)|$\{\_\_intSum\($\{\_\_P\(duration,180\)\},$\{\_\_P\(senderDelay,20\)\},30\)\}|
|Startup delay \(seconds\)|0|

**Thread Group「senders」**：

|**字段**|**值**|
|---|---|
|Number of Threads|$\{\_\_P\(threads,100\)\}|
|Ramp\-up period|$\{\_\_P\(rampup,30\)\}|
|Loop Count|Infinite|
|Specify Thread lifetime|勾|
|Duration \(seconds\)|$\{\_\_P\(duration,180\)\}|
|Startup delay \(seconds\)|$\{\_\_P\(senderDelay,20\)\}|

**启动时序要求**：接收者必须先于发送者建好连接，否则前几秒推给接收者的消息在 `ChannelManager` 里找不到 Channel，计入 `im_push_total{result="miss"}`，章节7\.1 对账就会出现差额。`senderDelay` 默认 20 s ≥ 接收者 `rampup`（默认 30 s）时请把 `senderDelay` 调到 ≥ rampup；章节6 命令里两者已配好。两个线程组的 `threads` 必须相同。

**JSR223 PreProcessor「pair\-id」**（挂在 Open Connection 下，两个线程组各一份，只差一行）：

|**字段**|**值**|
|---|---|
|Language|groovy|
|Cache compiled script if available|勾|
|Script（receivers 组）|见下|

```Groovy
int i = ctx.getThreadNum() + 1              // 线程组内 0 起，转成 1 起
vars.put('senderId',   String.valueOf(200000 + i))
vars.put('receiverId', String.valueOf(300000 + i))
vars.put('sessionId',  String.valueOf(400000 + i))
vars.put('userId',     vars.get('receiverId'))   // receivers 组：以接收者身份握手
```

senders 组把最后一行改成 `vars.put('userId', vars.get('senderId'))`。

**HTTP Header Manager**（挂在 Open Connection 下）：一行 `X-User-Id` = `${userId}`。（perf profile 下 `im.auth.enabled=false`，握手只认这个头。）

**WebSocket Open Connection**（Add → Sampler → WebSocket Open Connection）：

|**字段**|**值**|
|---|---|
|Name|open|
|TLS|不勾|
|Server name or IP|[localhost](http://localhost)|
|Port|9101|
|Path|/ws/netty|
|Connection timeout \(ms\)|5000|
|Read timeout \(ms\)|5000|

**WebSocket Single Read Sampler「recv」**（receivers 组，直接挂在线程组下）：

|**字段**|**值**|
|---|---|
|Connection|use existing connection|
|Data type|Text|
|Response \(read\) timeout \(ms\)|1000|
|Optional read|勾（超时不算失败，线程继续循环读）|

接收者只做这一件事：循环读帧、什么都不发。perf 下 `im.idle.reader-seconds=3600`，不发 ping 也不会被服务端踢掉。

**WebSocket request\-response Sampler「send」**（senders 组，直接挂在线程组下）：

|**字段**|**值**|
|---|---|
|Connection|use existing connection|
|Data type|Text|
|Response \(read\) timeout \(ms\)|10000|
|Request data|下面这段（一行）|

```JSON
{"sessionId":${sessionId},"receiverId":${receiverId},"senderId":${senderId},"type":0,"sessionType":0,"clientMessageId":"${cmid}","body":{"content":"${cmid}"}}
```

**JSR223 PreProcessor「cmid」**（挂在「send」下，每次发送前生成一个新的 clientMessageId）：

|**字段**|**值**|
|---|---|
|Language|groovy|
|Cache compiled script if available|勾|
|Script|vars\.put\('cmid', UUID\.randomUUID\(\)\.toString\(\)\)|

（不能在模板里直接写两个 `${__UUID()}`，那会生成两个不同的值。）

`content` 也填 `cmid`：`message` 表没有 `clientMessageId` 列，靠 `content` 唯一值做 章节7\.1 的不丢不重对账。

**Constant Timer**（send 下）：Thread Delay \(ms\) = `${__P(pacing,200)}`。每个发送者最多 5 msg/s，并发 1000 时约 5000 msg/s 目标。

**Response Assertion「echo clientMessageId」**（send 下）：

|**字段**|**值**|
|---|---|
|Field to Test|Text Response|
|Pattern Matching Rules|Contains|
|Patterns to Test|$\{cmid\}|

原理：push 消费者 `signalMessage` 先推发送者、再推接收者，发送者读到的下一帧就是自己这条消息的回显，帧里带同一个 `clientMessageId`。「send」采样器的响应时间 = 从写帧到收到回显 = 端到端时延。

### 3\.3 JMeter 在这两个场景下的能力边界

- **压测机与被测同机**：JMeter 的 JVM 与 4 个服务、Docker VM 抢同一批 CPU。响应时间里包含 JMeter 自身调度延迟，数字是「同机混部」下的数字，只能纵向比较各档，不能外推。做完 1\.2 与 1\.5 之后内存不再是瓶颈，剩下的天花板是 CPU：若压测中任务管理器 CPU 持续 100%（逻辑核 ≤ 8 时很可能），只能把 `.wslconfig` 的 `processors` 再调低一点给服务让路，并把 章节4 两个采集脚本的 `-Interval` 从 5 改成 10。

- **HTTP 场景并发上限**：RedPacketService Tomcat `max=200`、`accept-count=200`；线程数超过 200 后新增线程只是在 accept 队列里排队，响应时间线性变长而 TPS 不再涨。阶梯最高档设 400 是为了看到这个拐点，再往上没有信息量。JMeter 单实例在本机 loopback 下 400 线程可稳定产生 3～6 千 req/s，足够。

- **WebSocket 场景**：插件每线程一条连接、同步读写；接收者线程只能轮询读（1 s 超时），无法精确测「接收者收到」的时刻，所以端到端时延只用发送者回显来度量。每条连接约占 JMeter 1 个线程栈（默认 1 MB）\+ 缓冲，2000 连接（1000 发 \+ 1000 收）需要把 JMeter 堆调到 2 GB（章节6 命令里的 `HEAP`）。再往上 Windows 上单个 JMeter 进程的线程调度开销明显，本文阶梯止于 1000 对。

- **Ramp\-up**：WebSocket 握手在 perf 下不访问 Redis，但 1000 条连接仍建议 30 s 内建完（≈33 次/s），太快会把握手失败误计为业务失败。

- JMeter 无法直接看到 Kafka 积压与推送 miss，这两项只能靠 章节4 采样。

## 监控采集

### 4\.1 每 5 秒采样 Prometheus 端点 `collect-metrics.ps1`

**为什么**：JMeter 只看到同步响应；落库、推送、Kafka 排队都在异步链路里，只有这些指标能说明「异步链路是否跟上了」。脚本直接抓 `/actuator/prometheus` 文本，不需要装 Prometheus。

```PowerShell
@'
param([string]$Tag = "run", [int]$Interval = 5)
. "$PSScriptRoot\perf-env.ps1"
$out = "$PSScriptRoot\results\metrics_$Tag.csv"
New-Item -ItemType Directory -Force "$PSScriptRoot\results" | Out-Null

function Get-Prom([int]$port) {
  try { (Invoke-WebRequest "http://localhost:$port/actuator/prometheus" -UseBasicParsing -TimeoutSec 3).Content -split "`n" } catch { @() }
}
# 取指标值：name 精确匹配，labels 是必须同时出现的标签片段（顺序无关），多行时求和
function M([string[]]$lines, [string]$name, [string[]]$labels = @()) {
  $sum = 0.0; $hit = $false
  foreach ($l in $lines) {
    if (-not $l.StartsWith($name)) { continue }
    if ($l.Length -gt $name.Length -and $l[$name.Length] -ne '{' -and $l[$name.Length] -ne ' ') { continue }
    $ok = $true; foreach ($lab in $labels) { if (-not $l.Contains($lab)) { $ok = $false; break } }
    if (-not $ok) { continue }
    $v = ($l -split '\s+')[-1]; $sum += [double]$v; $hit = $true
  }
  if ($hit) { $sum } else { "" }
}
$hdr = "ts,rt_online,rt_push_ok,rt_push_fail,rt_push_miss,rt_pushdelay_count,rt_pushdelay_sum,rt_pushdelay_max,rt_cpu,rt_heap_mb," +
       "off_storedelay_count,off_storedelay_sum,off_storedelay_max,off_hikari_active,off_hikari_pending,off_cpu," +
       "rp_http_count,rp_http_sum,rp_http_max,rp_http_le50ms,rp_http_le200ms,rp_tomcat_busy,rp_hikari_active,rp_hikari_pending,rp_kafkadelay_count,rp_kafkadelay_sum,rp_kafkadelay_max,rp_cpu,rp_heap_mb"
Set-Content $out $hdr
"writing $out  (Ctrl+C to stop)"
while ($true) {
  $t0 = Get-Date
  $rt = Get-Prom $P_REALTIME; $off = Get-Prom $P_OFFLINE; $rp = Get-Prom $P_REDPACKET
  $uri = 'uri="/api/chat/redPacket/receive"'
  $row = @(
    $t0.ToString("HH:mm:ss"),
    (M $rt "im_channel_online"),
    (M $rt "im_push_total" @('result="ok"')), (M $rt "im_push_total" @('result="fail"')), (M $rt "im_push_total" @('result="miss"')),
    (M $rt "im_push_kafka_delay_seconds_count"), (M $rt "im_push_kafka_delay_seconds_sum"), (M $rt "im_push_kafka_delay_seconds_max"),
    (M $rt "process_cpu_usage"), ([math]::Round((M $rt "jvm_memory_used_bytes" @('area="heap"'))/1MB)),
    (M $off "im_store_kafka_delay_seconds_count"), (M $off "im_store_kafka_delay_seconds_sum"), (M $off "im_store_kafka_delay_seconds_max"),
    (M $off "hikaricp_connections_active"), (M $off "hikaricp_connections_pending"), (M $off "process_cpu_usage"),
    (M $rp "http_server_requests_seconds_count" @($uri)), (M $rp "http_server_requests_seconds_sum" @($uri)), (M $rp "http_server_requests_seconds_max" @($uri)),
    (M $rp "http_server_requests_seconds_bucket" @($uri,'le="0.05"')), (M $rp "http_server_requests_seconds_bucket" @($uri,'le="0.2"')),
    (M $rp "tomcat_threads_busy_threads"), (M $rp "hikaricp_connections_active"), (M $rp "hikaricp_connections_pending"),
    (M $rp "redpacket_receive_kafka_delay_seconds_count"), (M $rp "redpacket_receive_kafka_delay_seconds_sum"), (M $rp "redpacket_receive_kafka_delay_seconds_max"),
    (M $rp "process_cpu_usage"), ([math]::Round((M $rp "jvm_memory_used_bytes" @('area="heap"'))/1MB))
  ) -join ","
  Add-Content $out $row
  # 控制台同步打印关键列，压测中一眼能看到"被采到了"：红包场景看 rp_http / rp_kafka 是否在涨，消息场景看 online / push_ok
  $f = $row -split ","
  function D($s) { if ("$s" -ne "") { [double]$s } else { 0.0 } }     # 空串（服务没起 / 指标尚不存在）按 0 打印
  "{0}  online={1,5}  push_ok={2,9}  rp_http={3,9}  rp_kafka={4,9}  rp_hikari={5}/{6}  cpu rt/off/rp={7:P0}/{8:P0}/{9:P0}" -f `
    $f[0], $f[1], $f[2], $(if ($f[16]) { $f[16] } else { "n/a" }), $f[24], $f[22], $f[23], (D $f[8]), (D $f[15]), (D $f[27])
  $sleep = $Interval - ((Get-Date) - $t0).TotalSeconds
  if ($sleep -gt 0) { Start-Sleep -Seconds $sleep }
}
'@ | Set-Content -Encoding UTF8 .\collect-metrics.ps1
```

列的含义：

`rp_tomcat_busy` 只有在 Spring Boot 注册了 Tomcat 指标时才有值（默认没有，列为空就忽略，用 `rp_http_*` 判断排队）；

`*_count/_sum` 是累计值，两次采样相减再相除 = 该 5 秒窗口的平均值；

`*_max` 是 Micrometer 滑动窗口（约 2 分钟）内的最大值；

`rp_http_le50ms / rp_http_count` = 50 ms 内完成的比例（`slo` 桶来自 `application-perf.yml`）。

> **`rp_http_*`**** 为空不是没采到**：`http_server_requests_seconds{uri="/api/chat/redPacket/receive"}` 这组指标在服务启动后**第一次有请求打到 ****`/receive`**** 之前不存在**，`M` 函数找不到就写空串，控制台显示 `n/a`。压测一开始它就会变成数字。同理 `rp_tomcat_busy` 全程为空是因为 Spring Boot 默认不注册 Tomcat 线程池指标；想让它有值（开环档看排队拐点用得上），在 `application-perf.yml` 加 `server.tomcat.mbeanregistry.enabled: true` 后重启 RedPacketService。
> 
> 

### 4\.2 每 5 秒采样 Kafka consumer lag `collect-lag.ps1`

**为什么**：lag 是「异步链路是否跟得上」最直接的数字。三个消费组一次 `kafka-consumer-groups` 调用查完（多个 `--group`），避免每组起一个 JVM。

```PowerShell
@'
param([string]$Tag = "run", [int]$Interval = 5)
. "$PSScriptRoot\perf-env.ps1"
$out = "$PSScriptRoot\results\lag_$Tag.csv"
New-Item -ItemType Directory -Force "$PSScriptRoot\results" | Out-Null
$groups = "infinite-chat-push-group-0","infinite-chat-store-group","redpacket-receive-handler-group","redpacket-completed-handler-group"
Set-Content $out "ts,group,topic,partitions,lag_sum,lag_max"
"writing $out  (Ctrl+C to stop)"
while ($true) {
  $t0 = Get-Date
  $argLine = "--bootstrap-server $KAFKA_BOOT_IN --describe " + (($groups | ForEach-Object { "--group $_" }) -join " ")
  $lines = Invoke-Kafka kafka-consumer-groups $argLine 2>$null
  $agg = @{}
  foreach ($l in $lines) {
    $f = ($l.Trim() -split '\s+')
    if ($f.Count -lt 6 -or $f[0] -eq "GROUP") { continue }
    $key = "$($f[0]),$($f[1])"; $lag = 0; [int]::TryParse($f[5], [ref]$lag) | Out-Null
    if (-not $agg[$key]) { $agg[$key] = @{n=0;sum=0;max=0} }
    $agg[$key].n++; $agg[$key].sum += $lag; if ($lag -gt $agg[$key].max) { $agg[$key].max = $lag }
  }
  $ts = $t0.ToString("HH:mm:ss")
  foreach ($k in $agg.Keys) { Add-Content $out "$ts,$k,$($agg[$k].n),$($agg[$k].sum),$($agg[$k].max)" }
  # 控制台一行汇总：每个消费组的 lag_sum（组名取最后一段）。空闲全 0；压测中红包看 receive-handler，消息看 push/store
  $ts + "  " + (($agg.Keys | Sort-Object | ForEach-Object { "{0}={1}" -f (($_ -split ',')[0] -replace '^(infinite-chat-|redpacket-)',''), $agg[$_].sum }) -join "  ")
  $sleep = $Interval - ((Get-Date) - $t0).TotalSeconds
  if ($sleep -gt 0) { Start-Sleep -Seconds $sleep }
}
'@ | Set-Content -Encoding UTF8 .\collect-lag.ps1
```

**启动方式**：**不需要手动启动了**。第 6 章的 `run-stage.ps1` 会在每一档的第 3 步自动开这两个窗口、第 7 步自动关掉，`-Tag` 永远等于档名，

下面这个一键脚本留作备用（比如只想单独观察一段时间的指标、不压测时）：

```PowerShell
Set-Location C:\perf\infinitechat
@'
param([Parameter(Mandatory=$true)][string]$Tag, [int]$Interval = 5)
# 两个采集脚本各开一个独立 pwsh 窗口（-NoExit：脚本报错窗口也不会关），结束时到窗口里 Ctrl+C
foreach ($s in "collect-metrics.ps1", "collect-lag.ps1") {
  Start-Process pwsh -WorkingDirectory $PSScriptRoot -ArgumentList @("-NoExit", "-File", "$PSScriptRoot\$s", "-Tag", $Tag, "-Interval", $Interval)
}
"started collectors for stage '$Tag' -> results\metrics_$Tag.csv, results\lag_$Tag.csv"
'@ | Set-Content -Encoding UTF8 .\start-collectors.ps1
```

单独用它的时候（压测由 `run-stage.ps1` 管，不要两边同时开）：

```PowerShell
.\start-collectors.ps1 -Tag adhoc      # 结束时到那两个窗口里 Ctrl+C
```

**确认**（两个窗口现在每 5 秒各打印一行，压测中肉眼就能看到数字在涨）：

- `results\metrics_<stage>.csv` 每 5 秒多一行。**红包场景**看 `rp_http_count` 与 `rp_kafkadelay_count` 两列在压测开始后持续增长（压测前 `rp_http_*` 为空或停在冒烟留下的个位数，是正常的，见 章节4\.1 说明）；**消息场景**看 `rt_online` 等于当前 WebSocket 连接数、`rt_push_ok` 在涨。

- `results\lag_<stage>.csv` 每 5 秒多 4 行（4 个消费组各 1 行），空闲时 `lag_sum=0`。

- 一档只有 180 s，5 s 一采只有 36 行有数据；控速档 100/s 时 5 s 内 `rp_http_count` 只涨 500，别把「涨得少」当成「没采到」。

### 4\.3 怎么判读 lag 曲线

对每个 \(group, topic\) 把 `lag_sum` 画成时间曲线（Excel 打开 CSV 即可），只看三种形态：

|**形态**|**含义**|**结论**|
|---|---|---|
|锯齿：短暂上升、几秒内回到 0，峰值不超过几百|消费者跟得上，波动只是批处理粒度（max\-poll\-records=100）|该档异步链路有效|
|压测期间单调上升，停止后还要跑一段才归零|消费能力 \< 生产速度；这一档的同步 TPS 不是系统真实吞吐|报告里把上一档标为「异步链路可持续的最高档」，并记录停止后归零耗时|
|停止后某个分区 lag 卡住不动|该分区消费者在重试→DLT 循环或阻塞|看 章节7 的 DLT 与日志，本档结果作废|

关注对象：领取红包看 `redpacket-receive-handler-group / topic-redpacket-receive`；发送消息看 `infinite-chat-push-group-0 / push-topic`（决定时延）与 `infinite-chat-store-group / store-topic`（决定落库是否跟上）。

**参考值（实测，用来预估等待时间）**：

|**消费组**|**消费速率**|**说明**|
|---|---|---|
|redpacket\-receive\-handler\-group|≈250 条/s（3 分区各 ≈83/s）|每条一次 MySQL 事务（insert red\_packet\_receive \+ update user\_balance \+ balance\_log），rp\_hikari\_active 稳定 = 3 = 消费线程数。lag 归零耗时 ≈ lag 峰值 ÷ 250：30 万 lag 要 ≈20 分钟|
|infinite\-chat\-store\-group|\> 430 条/s（msg\_100 档未见积压，峰值 lag 287）|批量 insert message|
|infinite\-chat\-push\-group\-0|\> 860 次推送/s（峰值 lag 57）|进程内 Channel 写，几乎不排队|

> **两个场景之间也要等 lag 归零**。红包消费者在归零前会持续以 ≈250 事务/s 写 MySQL、占 3 个 Hikari 连接，这时开跑消息场景，`store-topic` 的排队时延与 OfflineDataService 落库速度都会被拖慢（实测 `msg_100` 在红包 lag 还有 24 万时启动的话，期间红包消费速率从 262/s 掉到 224/s，`store-topic` 排队均值 183 ms 将不可信，需重跑）。
> 
> 

## 压测前冒烟清单 `smoke.ps1`

**为什么**：把 章节1、章节2 的关键前提再自动过一遍，并各真实跑通一次两条链路（含异步落库）。**全绿才能开压**，任何 `[FAIL]` 都先处理。

冒烟用的红包不能动 CSV 里的行，所以脚本自己发一个 2 份的小红包给 `100001` 领。

```PowerShell
@'
. "$PSScriptRoot\perf-env.ps1"
$global:allOk = $true
function Check([string]$name, [bool]$ok, [string]$actual, [string]$expect) {
  $tag = if ($ok) { "[ OK ]" } else { $global:allOk = $false; "[FAIL]" }
  "{0} {1,-46} actual={2,-28} expect={3}" -f $tag, $name, $actual, $expect
}

# 1. 容器
foreach ($c in $MYSQL_C,$REDIS_C,$KAFKA_C,$NACOS_C,$CANAL_C) {
  $st = docker inspect --format '{{.State.Status}}' $c 2>$null
  Check "container $c" ($st -eq "running") "$st" "running"
}
# 2. health
foreach ($p in $P_OFFLINE,$P_REALTIME,$P_REDPACKET,$P_USER) {
  try { $s = (Invoke-RestMethod "http://localhost:$p/actuator/health" -TimeoutSec 3).status } catch { $s = "DOWN" }
  Check "health :$p" ($s -eq "UP") $s "UP"
}
# 3. 指标注册
$rt = (Invoke-WebRequest "http://localhost:$P_REALTIME/actuator/prometheus" -UseBasicParsing).Content
$off= (Invoke-WebRequest "http://localhost:$P_OFFLINE/actuator/prometheus" -UseBasicParsing).Content
$rp = (Invoke-WebRequest "http://localhost:$P_REDPACKET/actuator/prometheus" -UseBasicParsing).Content
Check "metric im_channel_online"                ($rt.Contains("im_channel_online")) "" "present"
Check "metric im_push_total"                    ($rt.Contains('im_push_total{')) "" "present"
Check "metric im_push_kafka_delay"              ($rt.Contains("im_push_kafka_delay_seconds_count")) "" "present"
Check "metric im_store_kafka_delay"             ($off.Contains("im_store_kafka_delay_seconds_count")) "" "present"
Check "metric redpacket_receive_kafka_delay"    ($rp.Contains("redpacket_receive_kafka_delay_seconds_count")) "" "present"
$hik = [regex]::Match($rp, 'hikaricp_connections_max\{[^}]*pool="RedPacketService-hikari"[^}]*\}\s+(\d+)').Groups[1].Value
Check "RedPacketService hikari max"             ($hik -eq "20") $hik "20"
$hik2 = [regex]::Match($off, 'hikaricp_connections_max\{[^}]*pool="OfflineDataService-hikari"[^}]*\}\s+(\d+)').Groups[1].Value
Check "OfflineDataService hikari max"           ($hik2 -eq "20") $hik2 "20"

# 4. Kafka 分区
$partExpect = [ordered]@{ "push-topic"=6; "store-topic"=6; "topic-redpacket-receive"=3 }
foreach ($t in $partExpect.GetEnumerator()) {
  $desc = Invoke-Kafka kafka-topics "--bootstrap-server $KAFKA_BOOT_IN --describe --topic $($t.Key)" 2>$null
  $pc = [regex]::Match(($desc -join "`n"), "Topic:\s*$([regex]::Escape($t.Key))\s+.*?PartitionCount:\s*(\d+)").Groups[1].Value
  Check "partitions $($t.Key)" ($pc -eq "$($t.Value)") $pc "$($t.Value)"
}

# 5. MySQL
$lc = (Invoke-MySql "SHOW VARIABLES LIKE 'lower_case_table_names'") -split "\s+" | Select-Object -Last 1
Check "mysql lower_case_table_names" ($lc -in "0","1","2") $lc "0 or 1 or 2"
# 只统计 500 份的压测红包，排除每次冒烟新增的 2 份红包
$seed = Invoke-MySql "SELECT CONCAT((SELECT COUNT(*) FROM user_session WHERE session_id=900001),'/',(SELECT COUNT(*) FROM red_packet WHERE session_id=900001 AND status=0 AND total_count=500))"
Check "seed members/packets(status=0)" ($seed -like "501/*") $seed "501/<PacketCount>"

# 6. Redis
Check "redis PING" ((Invoke-Redis PING) -eq "PONG") "" "PONG"
$firstId = (Get-Content "$PSScriptRoot\redpacket_ids.txt" | Select-Object -First 1)
Check "redis pool of first packet" ((Invoke-Redis LLEN "redpacket:${firstId}:pool") -eq "500") "" "500"
Check "redis session:members cache absent" ((Invoke-Redis EXISTS session:members:900001) -eq "0") "" "0"

# 7. 真实领取一次（独立冒烟红包，2 份 0.02 元）
$sendBody = @{ sessionId=900001; senderId=100000; receiverId=$null; type=3; sessionType=1; clientMessageId="smoke-"+[guid]::NewGuid(); body=@{ redPacketType=0; totalAmount=0.02; totalCount=2; redPacketWrapperText="smoke" } } | ConvertTo-Json -Compress
$s = Invoke-RestMethod "http://localhost:$P_REDPACKET/api/chat/redPacket/send" -Method Post -Body $sendBody -ContentType "application/json; charset=utf-8"
Check "smoke /send code" ($s.code -eq 200) "$($s.code) $($s.message)" "200"
$rpid = $s.data.redPacketId
$r = Invoke-RestMethod "http://localhost:$P_REDPACKET/api/chat/redPacket/receive" -Method Post -Body ("{""userId"":100001,""redPacketId"":$rpid}") -ContentType "application/json; charset=utf-8"
Check "smoke /receive message" ($r.data.message -eq "恭喜您，领取成功") "$($r.data.message)" "恭喜您，领取成功"
Start-Sleep -Seconds 3
$cnt = Invoke-MySql "SELECT COUNT(*) FROM red_packet_receive WHERE red_packet_id=$rpid AND receiver_id=100001"
Check "smoke receive persisted (kafka->mysql)" ($cnt -eq "1") $cnt "1"

# 8. 真实发一条单聊消息并等回显 + 落库
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.Options.SetRequestHeader("X-User-Id","200001")
$ct = [Threading.CancellationToken]::None
$ws.ConnectAsync([Uri]"ws://localhost:$P_NETTY/ws/netty", $ct).Wait(5000) | Out-Null
Check "ws handshake with X-User-Id" ($ws.State -eq "Open") "$($ws.State)" "Open"
$cmid = "smoke-" + [guid]::NewGuid().ToString()
$json = '{"sessionId":400001,"receiverId":300001,"senderId":200001,"type":0,"sessionType":0,"clientMessageId":"' + $cmid + '","body":{"content":"' + $cmid + '"}}'
$bytes = [Text.Encoding]::UTF8.GetBytes($json)
$ws.SendAsync([ArraySegment[byte]]::new($bytes), [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).Wait()
$buf = New-Object byte[] 65536
$task = $ws.ReceiveAsync([ArraySegment[byte]]::new($buf), $ct)
$echo = if ($task.Wait(5000)) { [Text.Encoding]::UTF8.GetString($buf, 0, $task.Result.Count) } else { "" }
Check "ws echo contains clientMessageId" ($echo.Contains($cmid)) ($echo.Substring(0,[math]::Min(40,$echo.Length))) "…$cmid…"
$ws.Dispose()
Start-Sleep -Seconds 3
$mc = Invoke-MySql "SELECT COUNT(*) FROM message WHERE content='$cmid'"
Check "ws message persisted (store-topic->mysql)" ($mc -eq "1") $mc "1"

# 9. DLT 为空
$dltTopics = [ordered]@{ "store-topic.DLT"=6; "push-topic.DLT"=6; "topic-redpacket-receive.DLT"=3 }
$dltExpectRows = ($dltTopics.Values | Measure-Object -Sum).Sum
$lineRe = '^\s*(\S+\.DLT):(\d+):(\d+)\s*$'
$dlt = Invoke-Kafka kafka-get-offsets "--bootstrap-server $KAFKA_BOOT_IN --topic '(store-topic|push-topic|topic-redpacket-receive)\.DLT'" 2>$null
$rows = @($dlt | Where-Object { $_ -match $lineRe })
if ($rows.Count -eq 0) {
  # 兜底：逐个 topic 查询（正则被 shell 转义吃掉时使用）
  $rows = @(foreach ($k in $dltTopics.Keys) {
    Invoke-Kafka kafka-get-offsets "--bootstrap-server $KAFKA_BOOT_IN --topic $k" 2>$null | Where-Object { $_ -match $lineRe }
  })
}
Check "DLT partitions found" ($rows.Count -eq $dltExpectRows) "$($rows.Count)" "$dltExpectRows"
$dltMax = if ($rows.Count -gt 0) { ($rows | ForEach-Object { [long](($_.Trim() -split ':')[-1]) } | Measure-Object -Maximum).Maximum } else { "n/a" }
Check "DLT topics empty" ("$dltMax" -eq "0") "$dltMax" "0"

# 10. 现在的 lag
$lag = Invoke-Kafka kafka-consumer-groups "--bootstrap-server $KAFKA_BOOT_IN --describe --group infinite-chat-push-group-0 --group infinite-chat-store-group --group redpacket-receive-handler-group" 2>$null
$lagMax = ($lag | Where-Object { $_ -notmatch '^GROUP|^\s*$' } | ForEach-Object { $f = ($_.Trim() -split '\s+'); if ($f.Count -ge 6) { $v = 0; [int]::TryParse($f[5],[ref]$v) | Out-Null; $v } } | Measure-Object -Maximum).Maximum
Check "consumer lag now" ($lagMax -eq 0) "$lagMax" "0"

""
if ($global:allOk) { "ALL GREEN - 可以开压" } else { "有 [FAIL] - 不要开压" }
'@ | Set-Content -Encoding UTF8 .\smoke.ps1
.\smoke.ps1
```

**确认**：最后一行 `ALL GREEN - 可以开压`。冒烟红包（2 份，只领了 1 份）24 小时后会被 `ExpirationDispatcher` 处理并退 1 分给 100000，对 章节7\.2 账务校验的影响已在 SQL 里排除（按 `total_count=500` 过滤）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MmE1MzI2ZDA3ZWRlZDA2ZDkyZmQ2MGQ4MGI1YjcxZGZfMzc4MGQxNjdkZjc4OWJkNmRjMDExZDkxMzg3NzEzNjFfSUQ6NzY4NjU5NjUxNzY0MTE0NTU2MF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

## 正式执行

### 6\.1 总体顺序

#### 6\.1\.1 名词对照（先统一说法，后面不再解释）

|**文档里的说法**|**大白话**|
|---|---|
|一「档」（stage）|一次压测。比如「50 个线程、每秒 100 次、压 180 秒」就是一档，档名 rp\_tps100|
|控速档（A 组）|踩着油门不放到底：固定 50 个线程，规定每秒只发多少次请求。用来找「后台落库跟得上的最高速度」|
|开环档（B 组）|油门踩到底：不限速，能发多快发多快。用来找「接口本身最快能到多少」|
|lag（积压）|请求已经返回成功了，但后台还没写进数据库的事件条数。lag 不归零就开下一档 = 两档数据互相污染|
|归零（drain）|积压被后台消化完、lag 回到 0 的过程。控速档通常几秒，开环档要 30～60 分钟|
|快照（snapshot）|把服务端的累计计数器记一笔。压测前记一次、压测后记一次，两者相减才是这一档真正的量|

#### 6\.1\.2 一次性准备：把脚本放进目录

把下面这些文件全部放到 `C:\perf\infinitechat\`（和 `perf-env.ps1`、两个`.jmx` 放一起）：

|**脚本**|**它负责什么**|**你会不会直接敲它**|
|---|---|---|
|run\-stage\.ps1|压测主入口。跑一档的全部 8 个步骤|会，第 6 章基本只敲这一条|
|summarize\-stage\.ps1|把一档的结果算成体检表，并写进 summary\_rp\.csv / summary\_msg\.csv|run\-stage 自动调用；想重看时可以单独敲|
|wait\-lag\-zero\.ps1|每 5 秒查一次积压，全部归零并稳定 15 秒后自动退出|run\-stage 自动调用；清理数据前也会用到|
|snapshot\.ps1|记一笔计数器快照（章节6\.4）|run\-stage 自动调用|
|new\-csv\-from\-spare\.ps1|从备用池 rp\_spare\.csv 里剪一份新 CSV|补档 / 某档作废重跑时会用|
|check\-perf\-data\.ps1|只读体检：现在库里、Redis 里、目录里到底有什么（章节2\.3）|清理前后各一次|
|reset\-perf\-data\.ps1|把上一轮数据全部清掉（章节2\.3）|重跑前一次|
|make\-csv\.ps1|生成 9 个档位 CSV（章节2\.2\.4）|造数时一次|

`collect-metrics.ps1`、`collect-lag.ps1`（章节4\.1、4\.2）保持原样不用改，`run-stage.ps1` 会自己去启动和关闭它们。章节4\.2 里的 `start-collectors.ps1` 现在用不到了，留着不影响。

放好之后先空跑一次，确认脚本能读到环境变量：

```PowerShell
Set-Location C:\perf\infinitechat
.\run-stage.ps1 -List
```

**确认**：打印出一张档位表（表头 \+ 13 个档位），`status` 列全是「未跑」。如果报「因为在此系统上禁止运行脚本」，先执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`（只对当前窗口生效）。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NDY2MDU5NzUxOWE2NTAyYTVmYTEzOGU5YTU1ZGI4ZTZfZGVhNjViNWVjMTAwN2Q0N2M0Mjg0MTYzMWVjNDRlN2ZfSUQ6NzY4NjU5Nzg1OTY5Njc3NDEyNV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 6\.1\.3 全局顺序：13 档，按这个顺序一档一档来

```Plain Text
红包场景（受 24 小时过期约束，必须先做）
  rp_warmup   预热，结果丢弃
  rp_tps100 ─┐
  rp_tps200  │ A 组：控速阶梯，找「后台落库跟得上的最高 TPS」
  rp_tps400  │ 每档 180 秒 + 几十秒归零
  rp_tps800 ─┘
      ↓ 看 A 组结论，决定要不要在中间补一档（章节6.2）
  rp_open50  ─┐
  rp_open200  │ B 组：开环，找「接口本身的极限」
  rp_open400 ─┘ 每档 60 秒 + 30～60 分钟归零 ← 时间大头
      ↓ 红包全部归零后才能开始消息场景
消息场景
  msg_warmup  预热，结果丢弃
  msg_100
  msg_200
  msg_500
  msg_1000
```

三条铁律，违反哪一条这一档就作废：

1. **一次只跑一档。** 不要开两个窗口同时压，也不要在等归零的时候顺手跑别的。

2. **不要用 JMeter 图形界面（GUI）跑压测。** GUI 自己就要吃掉大量 CPU，而且不会写 `.jtl`。GUI 只用来改脚本。

3. **上一档的积压没归零，不能开下一档。** `run-stage.ps1` 会自动拦住你，但你自己心里也要有数。

#### 6\.1\.4 一档的 8 个步骤（脚本自动做，你只要看输出）

敲一条命令，例如：

```PowerShell
Set-Location C:\perf\infinitechat
.\run-stage.ps1 rp_warmup      # 预热，看到 run_ok 之后直接进下一档
```

脚本会依次做这 8 件事，屏幕上会打印 `第 N 步 / 共 8 步`：

|**步**|**做什么**|**失败了会怎样**|
|---|---|---|
|1|开压前检查：脚本在不在、这一档是不是已经跑过、4 个服务健不健康、积压是不是 0、CSV 在不在 / 够不够行 / 有没有被用过、红包离过期还有多久|打印红色 \[FAIL\] 并直接退出。这时候压测还没开始，任何数据都没动，按提示修好后重新敲同一条命令就行|
|2|记录压测前的计数器（snapshot\.ps1 \-Tag \<档名\>\_before）|—|
|3|启动两个采集窗口，等 12 秒确认真的在写文件|12 秒后文件还是空的就报错退出，那两个窗口会留在屏幕上给你看报错|
|4|跑 JMeter（无界面模式）。这期间不要碰电脑，别开浏览器、别编译代码|JMeter 自己的报错会原样打印出来|
|5|等积压归零。每 5 秒打印一行当前 lag 和预计还要多久|开环档这里要等 30～60 分钟，属正常，不要关窗口|
|6|记录压测后的计数器（\_after 快照）|—|
|7|**关掉采集窗口，生成 HTML 报告**|—|
|8|打印体检表，并把这一行结果追加进 results\\summary\_rp\.csv（或 summary\_msg\.csv）|—|

中途想放弃：按 `Ctrl+C`。采集窗口会被自动关掉，但 `results\` 里会留下半截文件——这一档必须按 章节6\.5「中途中断了」处理后重跑。

#### 6\.1\.5 体检表怎么看

第 8 步会打印四组数字，最后给三个结论词：

|**结论词**|**什么意思**|**不满足怎么办**|
|---|---|---|
|run\_ok|这一档的数据干不干净：成功率 100%、跑满了时长、实际速度贴近目标、线程都起来了|False 就是废档，必须停下来查原因（章节6\.5），修好后重跑这一档|
|async\_ok|后台落库跟不跟得上：停压后 30 秒内积压归零|False 不是错误，是一个结论。它说明「可持续上限」在这一档以下。记下来，继续跑下一档|
|consistent|账对不对得上：JMeter 发了多少笔、服务端收了多少笔、Kafka 里多少条、数据库落了多少条，四个数字一致，且死信队列没有新增|False 要查（章节7\.4）。最常见的是积压还没真正归零|

行首的标记也是这个意思：`[ OK ]` 通过；`[FAIL]` 红字，**必须停**；`[WARN]` 黄字，是一个发现，记下来继续。

### 6\.2 领取红包阶梯

> **为什么分 A、B 两组**：最早实测发现，`/receive` 接口本身（只走 Redis Lua）能到 **≈10,500 TPS**，而领取事件落库的后台消费者只有 **≈250 条/s**，相差 40 倍。用「线程数」当阶梯完全分不出区别——最低档就把后台打爆 40 倍。所以改成两组：A 组固定 50 线程、用限速旋钮一级级抬高，找后台跟得上的最高速度；B 组不限速，单独测接口极限。
> 
> 

##### A 组：控速阶梯（每档 180 秒，归零几十秒）

|**档名（命令里直接用）**|**线程**|**每秒目标**|**时长**|**这一档想看什么**|
|---|---|---|---|---|
|rp\_warmup|20|100|60 s|预热：让 JIT、连接池、Lua 脚本缓存热起来。结果不进报告|
|rp\_tps100|50|100|180 s|基线。后台应该完全跟得上（async\_ok=True）|
|rp\_tps200|50|200|180 s|接近后台消费能力（≈250/s），开始逼近|
|rp\_tps400|50|400|180 s|预期开始积压，看归零要多久|
|rp\_tps800|50|800|180 s|明显积压，确认拐点在 400 以下|

##### B 组：开环档（每档 60 秒，但归零要 30～60 分钟）

|**档名**|**线程**|**限速**|**时长**|**这一档想看什么**|
|---|---|---|---|---|
|rp\_open50|50|不限|60 s|接口同步路径的极限吞吐（上一轮实测 ≈10,500 TPS、P99 5 ms）|
|rp\_open200|200|不限|60 s|正好等于 Tomcat 最大线程数 200，排队的起点|
|rp\_open400|400|不限|60 s|超过 200 之后：TPS 不再涨、响应时间线性变长，这就是排队拐点|

> B 组每档会瞬间产生 60 万条以上待落库事件，按 250 条/s 算要 40 分钟才能消化完，这段时间机器不能做别的。**时间不够就只做 ****`rp_open50`**** 一档**，它已经能回答「接口极限是多少」；`rp_open200`/`rp_open400` 是为了画出排队拐点，属于加分项。
> 
> 也可以用 `-Duration 30` 把开环档缩到 30 秒，归零时间直接减半，结论同样成立（跑满 30 秒即有效）：`.\run-stage.ps1 rp_open200 -Duration 30`
> 
> 

##### 命令：一档一条，从上到下

```PowerShell
Set-Location C:\perf\infinitechat

.\run-stage.ps1 rp_warmup      # 预热，看到 run_ok 之后直接进下一档
.\run-stage.ps1 rp_tps100
.\run-stage.ps1 rp_tps200
.\run-stage.ps1 rp_tps400
.\run-stage.ps1 rp_tps800
# —— A 组到此结束，先看下面「A 组跑完怎么下结论」——
.\run-stage.ps1 rp_open50
.\run-stage.ps1 rp_open200
.\run-stage.ps1 rp_open400
```

线程数、限速、时长、用哪个 CSV，全部写在 `run-stage.ps1` 顶部的档位表里，**不需要你手改任何参数**。想确认某一档的参数，敲 `.\run-stage.ps1 -List`。

**每一档的确认**（体检表里直接能看到）：

- 控速档：`成功率 = 100%`、`实际时长 ≈ 180 秒`、`实际 TPS / 目标` 偏差在 5% 以内 → `run_ok=True`

- 开环档：`成功率 = 100%`、`实际时长 ≥ 30 秒`、`线程启动数 = 目标线程数` → `run_ok=True`

- 所有档：`死信队列新增 = 0`、三个对账数字一致 → `consistent=True`



**A 组跑完怎么下结论（这是这一组的全部目的）**

打开 `results\summary_rp.csv`，或者直接敲 `.\run-stage.ps1 -List` 看 `async_ok` 那一列：

1. 找到**最高的 ****`async_ok=True`**** 的档**，记作 **X**；再找到**最低的 ****`async_ok=False`**** 的档**，记作 **Y**。

2. 如果 **Y ÷ X ≥ 2**（比如 200 跟得上、400 跟不上），中间还有一大段没测到，**补一档**取中间值（300）：

```PowerShell
# 补档要用一份没被用过的 CSV：从备用池剪 70,000 行（300 × 180 × 1.3 余量）
.\new-csv-from-spare.ps1 -Rows 70000 -Out rp_tps300.csv
.\run-stage.ps1 rp_tps300 -Csv rp_tps300.csv
```

3. 补完再看一次。**最多补两次就够了**，不必逼出精确值——报告里写「可持续吞吐在 200～300 TPS 之间，取保守值 200」是完全合格的结论。

4. 如果 `rp_tps100` 就已经 `async_ok=False`（后台连 100/s 都跟不上），说明消费能力比上一轮实测还低，**先别往下跑**，去看 章节7\.4 第 3 步的服务日志，确认不是数据库连接池打满或磁盘太慢。

补档的档名必须写成 `rp_tps<数字>` 的样子（`rp_tps150`、`rp_tps300`…），脚本才认得；线程固定 50、时长 180 秒。



#### 测试结果

具体压测数据：

rp\_tps100：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MGNmNzkxMWExZDdhOTI5OWJhODQ0YzdhYzIwMzU3ZDFfMDhiMDhiODIwYmJlOThkYTFmODAxOTk0NGRiOTE0YTNfSUQ6NzY4NjYwNDIzOTgxMTkwNjc0Nl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

rp\_tps200：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NTUwYTUxZTYzZGY2OTdjYTY3ZjRlZmRkYTA0OGM1ZDJfNmQzOGUwZTk3Yzk3YzE5MzJlNTI0NTRhMmY0YmFmOGZfSUQ6NzY4NjYwNDA0OTE0MzEyMjkxOF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

rp\_tps400：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZjkzMGM1NzU5MGI4NDE1OWU2NTk3Y2U3NzU1ZjM4M2VfYjMzZjZiNzhmN2QxMjU4ZTVjOWM1OWYyZDBlZjNhZGJfSUQ6NzY4NjYwMzkzMjIzMTAyNzk0NV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

rp\_tps800：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MmFmMjhkN2MwZjJiNTc2OTVjNjc1MzVkYjkyMmJlNTlfMjJhYmQ0MjVhOTI4ODA4ZTUxY2VjY2Q1NTM2ZTdmMzJfSUQ6NzY4NjYwNjk3NTAzMDc5MTE0MV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

#### 测试结果

rp\_open50：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=OTMxMjc1YmU5YjY4MTcwZTZjOTQ5OWJkN2Q1MjdmZmZfMGFmYWIxM2MwMmNhMjJhZjYzM2NiZWFhMGVkMDBmMGNfSUQ6NzY4NjgxODI4MzQzMzUxMjEyN18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

rp\_open200：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ODg4MGI3MjcxZWQwZjU3OGY4NzI5MzRkMTc3YzJmNzFfZDUxMTJhMmFlMjBkOTcyYjJjNzcwZmNmODIwMTQxNTFfSUQ6NzY4NjgxOTMyMzY1ODM0MTM1Nl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

rp\_open400：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MzFkZDVjZTcxNGIzYTk1ZjFkMjBkYWYyZGJkZTUzYThfY2Y3MzcwYTdjOWRkYmJjZjM4ZWNiODNhN2EwOTE0YjBfSUQ6NzY4NjgxOTU3Mzc1NjQ5Njg0OF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 6\.3 发送消息阶梯

|**档名**|**发送者 / 接收者线程**|**总连接数**|**时长**|**每个发送者的发送间隔**|
|---|---|---|---|---|
|msg\_warmup|20 / 20|40|60 s|200 ms|
|msg\_100|100 / 100|200|180 s|200 ms|
|msg\_200|200 / 200|400|180 s|200 ms|
|msg\_500|500 / 500|1000|180 s|200 ms|
|msg\_1000|1000 / 1000|2000|180 s|200 ms|

```PowerShell
Set-Location C:\perf\infinitechat

.\run-stage.ps1 msg_warmup
.\run-stage.ps1 msg_100
.\run-stage.ps1 msg_200
.\run-stage.ps1 msg_500
.\run-stage.ps1 msg_1000
```

几个和红包场景不一样的地方： 

- **不用 CSV**。消息场景的 userId / sessionId 是按线程号算出来的（章节3\.2），不读文件，所以这 5 档可以反复重跑，不存在「数据被用过」的问题。

- **接收者必须先连上**。脚本已经把 `senderDelay` 设成比 `rampup` 大 5 秒，发送者一定晚于接收者启动。体检表里 `推送 miss / fail` 必须是 `0 / 0`，不是 0 就说明时序还是错了（章节7\.4 第 6 条）。

- **开压前必须「在线连接 = 0」**。上一档的 WebSocket 连接如果还没断干净，这一档的 `在线连接峰值` 就会偏大。第 1 步会自动检查，报 `[FAIL]` 就等 10 秒再敲一次。

- **`msg_1000`**** 要 2000 条连接**，JMeter 自己就要吃 2 GB 堆（脚本已自动设好）。如果这一档 `建连失败数 > 0` 或者机器明显卡死，就到 `msg_500` 为止，在报告里写明「受压测机限制，最高测到 500 对连接」——这是合格的结论，不是失败。

**每一档的确认**：`建连失败数 = 0`、`发送成功率 = 100%`、`在线连接峰值 = 2 × 线程数`、`推送 miss / fail = 0 / 0` → `run_ok=True`。





#### 结论：可持续的最高档在 200～500 之间，保守取 200

msg\_100：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZWYyYzQ1MTIyNzhhNmZmMmRhMGM2MjE5NTYyY2VkYzlfNzA5NDJjYzM5NWZjODM4NzlhZmYyMzcyY2I4ZDFhODhfSUQ6NzY4NjgyMDI1MDQxMTI3MzQxNV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

msg\_200：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MTRjZjczMmRmMWQ0NjdmMDZlODg4YzU1ZDAwYjZjOTdfMGY0NDhjNDIyYjZiM2Y3MzQ1ZTIxMDNlMDI1YTFjZWRfSUQ6NzY4NjgyMDM1ODAwNzkxNzUwNl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

msg\_500：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZGUwNTc0ZGQyZDhiMDUzMDE5Mjg0MmVlMzEyMTIzZWVfNDU2YTdjZDgxOWRlY2I2ZDg3YmZmZGY2MWMyYTE5ZTNfSUQ6NzY4NjgyMDQ1OTk3OTg1Mjc3Nl8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

msg\_1000：

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=OGE4OGE3YWRkYWVkZTJhYTRhYjJlODcyYzY2MThiMTFfMmEyM2Q0Yzg1OWI2Yjc5YjZiZjZkYjZjNzQ3ZTJiNWRfSUQ6NzY4NjgyMDU4ODgyNDgyNTAxOV8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)





### 6\.4 每档自动记录的计数器快照

**为什么要有这个**：`im_push_total`、`http_server_requests_seconds_count` 这类指标是**进程启动以来的累计值**，服务不重启就不会清零。比如上一轮 `rt_push_miss` 的绝对值是 300 万，但那全是造数时给离线群成员推送产生的，和压测无关。所以第 7 章所有对账都必须用「压测后 − 压测前」的差值。

`run-stage.ps1` 已经在第 2 步和第 6 步自动各记一次，你不用手敲。下面是脚本内容（已放进 `C:\perf\infinitechat\snapshot.ps1`）：

```PowerShell
@'
param([string]$Tag)
# 作用：把此刻的累计计数器（指标 + MySQL 行数）记一行到 results\snapshots.csv，对账时用 after - before
. "$PSScriptRoot\perf-env.ps1"
New-Item -ItemType Directory -Force "$PSScriptRoot\results" | Out-Null
$rt = (Invoke-WebRequest "http://localhost:$P_REALTIME/actuator/prometheus" -UseBasicParsing).Content -split "`n"
$rp = (Invoke-WebRequest "http://localhost:$P_REDPACKET/actuator/prometheus" -UseBasicParsing).Content -split "`n"
$off= (Invoke-WebRequest "http://localhost:$P_OFFLINE/actuator/prometheus" -UseBasicParsing).Content -split "`n"
function V($lines, $pat) { ($lines | Where-Object { $_ -match $pat } | ForEach-Object { [double](($_.Trim() -split '\s+')[-1]) } | Measure-Object -Sum).Sum }
$snap = [ordered]@{
  ts = (Get-Date).ToString("s"); tag = $Tag
  push_ok   = V $rt 'im_push_total\{.*result="ok"'
  push_fail = V $rt 'im_push_total\{.*result="fail"'
  push_miss = V $rt 'im_push_total\{.*result="miss"'
  push_delay_count = V $rt '^im_push_kafka_delay_seconds_count'
  push_delay_sum   = V $rt '^im_push_kafka_delay_seconds_sum'
  store_delay_count= V $off '^im_store_kafka_delay_seconds_count'
  store_delay_sum  = V $off '^im_store_kafka_delay_seconds_sum'
  rp_http_count = V $rp '^http_server_requests_seconds_count\{.*uri="/api/chat/redPacket/receive"'
  rp_http_sum   = V $rp '^http_server_requests_seconds_sum\{.*uri="/api/chat/redPacket/receive"'
  rp_kafka_count= V $rp '^redpacket_receive_kafka_delay_seconds_count'
  rp_kafka_sum  = V $rp '^redpacket_receive_kafka_delay_seconds_sum'
  mysql_message_perf   = (Invoke-MySql "SELECT COUNT(*) FROM message WHERE session_id BETWEEN 400001 AND 410000")
  mysql_rp_receive_perf= (Invoke-MySql "SELECT COUNT(*) FROM red_packet_receive r JOIN red_packet p ON p.red_packet_id=r.red_packet_id WHERE p.session_id=900001 AND p.total_count=500")
}
$line = ($snap.Values -join ",")
if (-not (Test-Path "$PSScriptRoot\results\snapshots.csv")) { Set-Content "$PSScriptRoot\results\snapshots.csv" (($snap.Keys) -join ",") }
Add-Content "$PSScriptRoot\results\snapshots.csv" $line
$snap
'@ | Set-Content -Encoding UTF8 .\snapshot.ps1
```

**确认**：每跑完一档，`results\snapshots.csv` 会多两行，`<档名>_before` 和 `<档名>_after`，且 `after` 的各个计数都 ≥ `before`。

想手动补一次（比如某一档中途中断、快照缺失）：

```PowerShell
.\snapshot.ps1 -Tag rp_tps100_after
```

### 6\.5 出问题怎么办

#### 第 1 步就被拦下（红色 \[FAIL\]，压测还没开始）

|**提示里出现**|**真正的原因**|**怎么处理**|
|---|---|---|
|服务 8101/8102/8103/8104 健康 DOWN|对应服务没起来，或起的时候没带 perf profile|回 章节1\.5 重新启动；看那个服务的 cmd 窗口里的报错|
|Kafka lag 为 0 \[FAIL\] 当前 lag=xxxxx|上一档的积压还没消化完|敲 \.\\wait\-lag\-zero\.ps1，等它打印「已归零」，再回来重跑本档|
|本档还没跑过 \[FAIL\]|这一档之前跑过，results\\\<档名\>\.jtl 已经存在|确认要作废重跑就加 \-Redo。红包档还必须同时换一份没用过的 CSV（见下一条）|
|CSV 没被用过 \[FAIL\] 已被用过：…|这份 CSV 的行已经被领过了，再用全部会返回「您已经领取过该红包了」|剪一份新的：\.\\new\-csv\-from\-spare\.ps1 \-Rows 40000 \-Out rp\_tps100\_b\.csv，再 \.\\run\-stage\.ps1 rp\_tps100 \-Redo \-Csv rp\_tps100\_b\.csv|
|CSV 行数够用 \[FAIL\]|这一档要的行数比文件里有的多|同上，从备用池剪一份更大的|
|红包没过期 \[FAIL\]|离 24 小时过期只剩不到 5 分钟，或红包在 Redis 里已经没了|这批红包报废了，回 章节2\.3 全量清理后重新造数|
|在线连接为 0 \[FAIL\] 当前 200|上一档的 WebSocket 还没断干净|等 10～20 秒重敲；还不行就看 RealTimeService 是不是卡住了|

#### 跑完了，但体检表有红字

|**体检表里**|**说明什么**|**怎么处理**|
|---|---|---|
|实际 TPS / 目标 远大于目标（比如目标 100、实际 10000）|限速没生效：\.jmx 里的 Constant Throughput Timer 被删了或改了|用 GUI 打开 redpacket\_receive\.jmx 核对章节3\.1 的「tps」计时器，Target throughput 必须是 $\{\_\_jexl3\($\{\_\_P\(tps,100\)\} \* 60\)\}，计算模式不能选 shared|
|实际时长 明显短于 180 秒|CSV 的行提前用完了|换更大的 CSV 重跑（\-Redo \-Csv）|
|成功率 不是 100%，失败原因是 已经领取过|这份 CSV 被用过第二次|换一份没用过的 CSV 重跑|
|失败原因是 红包已被领完|某个红包的 500 份被别处领走了（比如冒烟脚本误用了 CSV 里的行）|回 章节2\.3 全量清理重造|
|失败原因里有 92005 或 无权领取|allow 名单里没这个用户 = 造数时群成员缓存没清|回 章节2\.2\.2 清缓存，然后重新造数|
|失败原因是 Connection timed out / Read timed out|机器扛不住了，或服务卡了|先看任务管理器 CPU 是不是 100%；是的话按 章节3\.3 调低 \.wslconfig 的 processors|
|Hikari 占用/排队峰值 排队 \> 0（黄字）|数据库连接池（20 个）在这一档被打满|这是结论不是故障，记进报告|
|死信队列新增 \> 0|有事件重试 2 次仍失败，进了死信队列|按 章节7\.4 第 2 步查死信内容和服务日志，这一档作废|
|MySQL 新增领取记录 比 JMeter 成功数少|积压其实还没真正归零，或有事件进了死信|先 \.\\wait\-lag\-zero\.ps1 再敲 \.\\summarize\-stage\.ps1 \<档名\> 重算一次|

#### 中途中断了（按了 Ctrl\+C，或者电脑重启了）

`results\` 里会留下半截的 `.jtl`、缺 `after` 的快照。处理办法：

```PowerShell
# 1. 先确认积压归零
.\wait-lag-zero.ps1
# 2. 红包档：这一档消耗掉的 CSV 行回不来了，必须换一份新的
.\new-csv-from-spare.ps1 -Rows 40000 -Out rp_tps200_b.csv
.\run-stage.ps1 rp_tps200 -Redo -Csv rp_tps200_b.csv
# 3. 消息档：不涉及 CSV，直接重跑
.\run-stage.ps1 msg_200 -Redo
```

加 `-Redo` 时旧文件不会被删，会被移到 `results\_discarded\` 里留档。

#### 其它

- **HTML 报告没生成**：单独补一次 `& "$JMETER_HOME\bin\jmeter.bat" -g .\results\<档名>.jtl -o .\results\<档名>_html`，再敲 `.\summarize-stage.ps1 <档名>`。

- **想重新看某一档的体检表**：`.\summarize-stage.ps1 rp_tps200`。这个脚本只读 `results\` 里的文件，不连服务，可以随便反复敲。

- **采集窗口 12 秒后还是空的**：那两个窗口会留在屏幕上，看里面的红字。最常见的是 `perf-env.ps1` 里 `$KAFKA_BOOT_IN` 填的地址在容器里连不上（章节1\.4）。

### 6\.6 跑完之后：结果文件在哪、怎么填报告

`C:\perf\infinitechat\results\` 里每一档会有这些文件：

|**文件**|**内容**|**第 8 章哪里用得到**|
|---|---|---|
|summary\_rp\.csv / summary\_msg\.csv|每档一行的汇总表，列的顺序就是 章节8\.2 / 8\.3 表格的顺序|直接用 Excel 打开照抄，这是主要来源|
|\<档名\>\_html\\index\.html|JMeter 官方 HTML 报告|核对响应时间分位数、看曲线|
|\<档名\>\.jtl|每一次请求的明细（几十万行）|章节7 对账时用|
|metrics\_\<档名\>\.csv|每 5 秒一行的服务端指标|画 CPU / 连接池曲线|
|lag\_\<档名\>\.csv|每 5 秒一行的积压|画 lag 曲线（章节4\.3）|
|\<档名\>\_run\.json|这一档用的参数、起止时间、归零耗时、死信前后值|填「停止后 lag 归零耗时」这一列|
|snapshots\.csv|所有档的 before / after 快照|章节7 全部对账|
|\<档名\>\_console\.txt|这一档屏幕上打印的全部内容|出问题时回看|

填报告的顺序：

1. 先填 章节8\.1 环境信息（章节1\.1、1\.2 采到的镜像 tag 和资源配额）。

2. 章节8\.2、8\.3 的每档一行，**直接从 ****`summary_rp.csv`**** / ****`summary_msg.csv`**** 抄**。「有效」这一列填体检表给的 `run_ok`；`async_ok=False` 的档在备注里写「后台落库跟不上」。

3. 做完 章节7 的一致性校验，把三组对账数字填进 8\.2 / 8\.3 下面的结论行。

4. 章节8\.4 的结论，用 章节6\.2「A 组跑完怎么下结论」得到的那个数字来写。

> **上一轮已经填在 8\.2 里的 ****`rp_50`**** 那一行**：它的原始数据（红包、领取记录）在 章节2\.3 清理时已经删掉了，对账数字不再可复现。处理办法是保留这一行，但在「有效」列注明「上一轮，数据已清理，仅作同步路径参考」，本轮用 `rp_open50` 重新测一次同样的内容。`msg_100` 那一行同理，本轮重跑后覆盖。
> 
> 

### 6\.7 时间预算（提前安排好，别跑到一半红包过期）

|**阶段**|**压测时间**|**等归零**|**小计**|
|---|---|---|---|
|清理 \+ 重新造数（章节2\.3）|—|—|**约 15 分钟**|
|冒烟（章节5）|—|—|2 分钟|
|A 组 5 档（含预热）|15 分钟|每档 ≤1 分钟|**约 25 分钟**|
|补档 0～2 档|0～6 分钟|—|0～10 分钟|
|B 组 rp\_open50|1 分钟|**40 分钟**|**约 45 分钟**|
|B 组 rp\_open200 \+ rp\_open400（可选）|2 分钟|**每档 40～60 分钟**|**1\.5～2 小时**|
|消息场景 5 档|15 分钟|每档 ≤1 分钟|**约 25 分钟**|
|章节7 一致性校验|—|—|15 分钟|

- **最短路线**（只做 `rp_open50`，跳过 `rp_open200/400`）：**约 2 小时 15 分钟**。

- **完整路线**：**约 4 小时**。

- 红包从造完那一刻起 24 小时过期，两条路线都远远够用；但**不要跨夜**——放着过夜，第二天红包就过期了，得从头再造一次。

- B 组归零的 40 分钟里机器不能做别的。建议把 B 组安排在你能离开电脑的时间段，A 组和消息场景安排在你能盯着屏幕的时候。

## 压测后一致性校验

**先看一下有没有必要手工做**：每一档跑完时，`run-stage.ps1` 的第 8 步（体检表「四、对账」那一段）已经自动做过一遍差值对账——JMeter 样本数 / 服务端请求数 / Kafka 事件数 / MySQL 落库数四个数字是否一致、死信队列有没有新增。**如果每一档的 ****`consistent`**** 都是 ****`True`****，本章只需要做 章节7\.3 的账务校验**（那是体检表没覆盖的、跨所有档的钱的守恒），其余小节当作出问题时的排查手册来用。

下面的步骤用于：`consistent=False` 时逐项定位，或者报告里需要把 MySQL 侧的 `distinct` 去重数、分区覆盖等明细写清楚。

先等所有 lag 归零（章节4\.2），再做下面校验。所有对账都用「after − before」差值。

### 7\.1 发送消息：不丢不重

**JMeter 侧发送数 ****`N_sent`**（`send` 标签的全部样本数，含失败——每个样本都发出了一帧）：

```PowerShell
$stage = "msg_100"
$jtl = Import-Csv .\results\$stage.jtl
$send = $jtl | Where-Object label -eq "send"
$N_sent = $send.Count; $N_ok = ($send | Where-Object success -eq "true").Count
"N_sent={0} N_ok={1} err={2}" -f $N_sent, $N_ok, ($N_sent - $N_ok)
# 本档消息所属会话范围：400001 .. 400000+threads
```

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=ZDBhMDUwNzRiNDI1ZDJhM2I2Yzg5YjY5MGQyNmNiZjFfMzFjMzgyOGZhYmUwZjZmYjMwZjBhYzE3NWIyOGFiMmVfSUQ6NzY4NjIyODYxNzcyMTg0Mjk2Ml8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

**MySQL 侧**（把 `100` 换成本档线程数；`content` 就是 `clientMessageId`，每条唯一）：

```PowerShell
Invoke-MySql "SELECT COUNT(*) total, COUNT(DISTINCT content) distinct_content, COUNT(DISTINCT message_id) distinct_id FROM message WHERE session_id BETWEEN 400001 AND 400100 AND created_time >= NOW() - INTERVAL 30 MINUTE;"
```

上面按时间窗过滤是为了不混入前面档次；更严谨的做法是用 章节6\.4 快照的 `mysql_message_perf` 差值。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=YzExNmRlZGEzZjBjNTYwZTQyNjgyZjY5ZmE0NDU0NTdfZDk5Nzc0OTE0M2JjMGMwZjM0MWM0YWYyYjRlMDQxNGZfSUQ6NzY4NjIyODY4NTIzNzU3MDc4NF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

**指标侧**（`snapshots.csv` 中同档 after − before）：

|**项**|**预期**|
|---|---|
|mysql\_message\_perf 差值|= N\_sent（不丢）|
|MySQL total = distinct\_content = distinct\_id|相等（不重）|
|push\_ok 差值|= 2 × N\_sent（发送者回显 \+ 接收者各一次）|
|push\_miss 差值|= 0（若 \> 0：接收者晚于发送者建连，见 章节3\.2 时序）|
|push\_fail 差值|0|
|push\_delay\_count 差值|= N\_sent（每条消息进 push\-topic 一次）|
|store\_delay\_count 差值|= N\_sent（每条消息进 store\-topic 一次）|
|push\_delay\_sum / push\_delay\_count（差值相除）|本档 push\-topic 平均排队时延（秒）|
|store\-topic\.DLT、push\-topic\.DLT 末尾 offset|与压测前一致（不进死信）|

DLT offset：

```PowerShell
Invoke-Kafka kafka-get-offsets "--bootstrap-server $KAFKA_BOOT_IN --topic '(store-topic|push-topic|topic-redpacket-receive)\.DLT'"
```

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=MjViNmJhZGE2NThiMTg3MjI2ZGNmZmRmNjc5MzY0NDJfZmQzMGZlOGU3YmU5ZDI3YWQ3MzNjOWM5NDEzYTNmMTNfSUQ6NzY4NjIyODgyMjk0OTEyMDk5Ml8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

**分区覆盖验证**（每档只需看一次）：

```PowerShell
Invoke-Kafka kafka-consumer-groups "--bootstrap-server $KAFKA_BOOT_IN --describe --group infinite-chat-push-group-0"
```

6 个分区的 `CURRENT-OFFSET` 在压测前后都应有增长（对比 before/after 两次输出），说明 sessionId 铺满了 6 个分区。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=NDk5ZGYzNjJjOWVjNjM3ZTY1NDcyYTdjMmEzOTNiMzdfN2YzZDhlNTM0ZTk4MTcxNTA4YWQzNTAxOTFlMTE3MzJfSUQ6NzY4NjIyODg5ODI5MTUzNDgyM18xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 7\.2 领取红包：Redis 领取记录数 vs MySQL 落库数 vs JMeter 成功数

**JMeter 成功数 ****`N_ok`**（三层断言全过的样本 = 首次领取成功 = 写了一条 Kafka 领取事件）：

```PowerShell
$stage = "rp_100"
$jtl = Import-Csv .\results\$stage.jtl
$N_all = $jtl.Count; $N_ok = ($jtl | Where-Object success -eq "true").Count
"samples={0} ok={1} failed={2}" -f $N_all, $N_ok, ($N_all - $N_ok)
$jtl | Where-Object success -eq "false" | Group-Object failureMessage | Select-Object Count, Name | Sort-Object Count -Descending | Select-Object -First 5
```

**Redis 侧**：所有压测红包的 `records` Hash 字段数之和（一次管道查完）：

```PowerShell
. .\perf-env.ps1
$ids = Get-Content .\redpacket_ids.txt
$res = ($ids | ForEach-Object { "HLEN redpacket:${_}:records" }) | docker exec -i $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0
$redisRecords = ($res | ForEach-Object { [long]$_ } | Measure-Object -Sum).Sum
$res2 = ($ids | ForEach-Object { "LLEN redpacket:${_}:pool" }) | docker exec -i $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0
$redisPoolLeft = ($res2 | ForEach-Object { [long]$_ } | Measure-Object -Sum).Sum
$res3 = ($ids | ForEach-Object { "GET redpacket:${_}:state" }) | docker exec -i $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0
$redisCompleted = ($res3 | Where-Object { $_ -eq "1" }).Count
"redis: records_total={0} pool_left={1} completed_packets={2}" -f $redisRecords, $redisPoolLeft, $redisCompleted
```

**MySQL 侧**：

```PowerShell
Invoke-MySql "SELECT COUNT(*) rows_total, COUNT(DISTINCT red_packet_id, receiver_id) rows_distinct, SUM(amount) amount_fen FROM red_packet_receive r JOIN red_packet p ON p.red_packet_id=r.red_packet_id WHERE p.session_id=900001 AND p.total_count=500;"
Invoke-MySql "SELECT status, COUNT(*) FROM red_packet WHERE session_id=900001 AND total_count=500 GROUP BY status;"
```

**对账表**（累计到当前为止，跨档累加；也可用 章节6\.4 快照的 `mysql_rp_receive_perf` 差值按档核对）：

|**项**|**预期**|
|---|---|
|Σ 各档 N\_ok（含冒烟/预热）|= redis records\_total（Redis 是同步写入，等于 JMeter 首次领取成功数）|
|MySQL rows\_total|= redis records\_total（lag 归零后；差额 = 还在队列里或进了 DLT）|
|redis records\_total \+ pool\_left|= PacketCount × 500|
|redis completed\_packets|= MySQL status=1 的红包数 = pool 已被删除的红包数|
|MySQL amount\_fen|= rows\_total（每份 1 分）|

### 7\.3 红包账务校验 SQL

**为什么**：钱的总量守恒：发送者扣的 = 领取者拿到的 \+ 池子里剩的（24 小时内没有退款）。

```PowerShell
Invoke-MySql @"
SELECT
  (SELECT balance FROM user_balance WHERE user_id=100000)                                   AS sender_balance,
  1000000000 - (SELECT SUM(total_amount) FROM red_packet WHERE session_id=900001)           AS sender_balance_expected,
  (SELECT SUM(balance) FROM user_balance WHERE user_id BETWEEN 100001 AND 100500)           AS receivers_balance,
  (SELECT SUM(amount) FROM red_packet_receive r JOIN red_packet p ON p.red_packet_id=r.red_packet_id WHERE p.session_id=900001) AS receivers_balance_expected,
  (SELECT SUM(amount) FROM balance_log WHERE user_id=100000 AND type=0)                     AS log_send_sum,
  (SELECT -SUM(total_amount) FROM red_packet WHERE session_id=900001)                       AS log_send_expected,
  (SELECT SUM(amount) FROM balance_log WHERE user_id BETWEEN 100001 AND 100500 AND type=1)  AS log_receive_sum,
  (SELECT COUNT(*) FROM balance_log WHERE user_id BETWEEN 100001 AND 100500 AND type=1)     AS log_receive_rows,
  (SELECT COUNT(*) FROM balance_log WHERE user_id BETWEEN 100000 AND 100500 AND type=2)     AS refund_rows;
"@
Invoke-MySql @"
SELECT COUNT(*) AS users_with_mismatch FROM (
  SELECT b.user_id, b.balance, IFNULL(SUM(l.amount),0) AS log_sum
  FROM user_balance b LEFT JOIN balance_log l ON l.user_id=b.user_id
  WHERE b.user_id BETWEEN 100001 AND 100500 GROUP BY b.user_id, b.balance
  HAVING b.balance <> log_sum) x;
"@
```

**预期**：`sender_balance = sender_balance_expected`；`receivers_balance = receivers_balance_expected = log_receive_sum`；`log_send_sum = log_send_expected`；`log_receive_rows` = `MySQL rows_total`（章节7\.2）；`refund_rows = 0`（24 小时内）；`users_with_mismatch = 0`。

![Image](https://internal-api-drive-stream.feishu.cn/space/api/box/stream/download/authcode/?code=M2M2MmIxMjdhMjdmYjA1M2UzMmU0NDE4YzFiZWJmY2FfZWNiNTE1Njg5ZTBkNWU3ZjYzNDY2Y2JmNWQ5ZjljNjFfSUQ6NzY4NjIyOTY4OTc1MjQ3MjUzNF8xNzk1MjcxNTc1OjE3OTUzNTc5NzVfVjM)

### 7\.4 差额不为 0 时的排查顺序

按顺序查，找到第一个不对的就停：

1. **lag 还没归零** → 再等，重新算。`redpacket-receive-handler-group` 归零前 MySQL 一定比 Redis 少。

2. **DLT 有消息** → 有事件重试 2 次后仍失败：

```PowerShell
Invoke-Kafka kafka-get-offsets "--bootstrap-server $KAFKA_BOOT_IN --topic '(store-topic|push-topic|topic-redpacket-receive)\.DLT'"
Invoke-Kafka kafka-console-consumer "--bootstrap-server $KAFKA_BOOT_IN --topic topic-redpacket-receive.DLT --from-beginning --max-messages 5 --timeout-ms 5000"
```

1. DLT 里的条数 = 差额，则原因在消费者异常，看第 3 步日志。

2. **服务日志 ERROR**（perf 下只写文件，根级 WARN）：

```PowerShell
Select-String -Path "<项目根目录>\logs\RedPacketService.log" -Pattern "ERROR" | Select-Object -Last 20
Select-String -Path "<项目根目录>\logs\OfflineDataService.log" -Pattern "ERROR" | Select-Object -Last 20
Select-String -Path "<项目根目录>\logs\RealTimeService.log" -Pattern "ERROR|推送失败" | Select-Object -Last 20
```

1. 常见：`Connection is not available, request timed out after 3000ms` = Hikari 池（20）在该档打满（`metrics_*.csv` 的 `rp_hikari_pending` 列 \> 0 能印证）。

2. **`红包领取事件重复投递`****（WARN）** → Redis 与 JMeter 一致、MySQL 也一致但日志有 WARN：这是 Kafka 至少一次投递被唯一索引拦下，属预期，不算差额。

3. **JMeter 失败样本的 ****`failureMessage`** → `已经领取过` 说明 CSV 文件复用了（章节2\.2\.4）；`红包已被领完` 说明该红包份数被别的档提前用掉了（比如冒烟脚本误用了 CSV 里的行）；`code=92005` 说明 `allow` 快照不含该用户（章节2\.2\.2 缓存没清）。

4. **`push_miss > 0`**（消息场景）→ 对照 `metrics_*.csv` 里 `rt_online` 达到 `2×threads` 的时刻与发送者开始的时刻，若发送者先到，调大 `senderDelay`。

5. **逐红包定位**：找出 Redis 与 MySQL 不一致的具体红包：

```PowerShell
$ids = Get-Content .\redpacket_ids.txt
$hl = ($ids | ForEach-Object { "HLEN redpacket:${_}:records" }) | docker exec -i $REDIS_C redis-cli -a $REDIS_PWD --no-auth-warning -n 0
$my = @{}; (Invoke-MySql "SELECT red_packet_id, COUNT(*) FROM red_packet_receive GROUP BY red_packet_id") | ForEach-Object { $f = $_ -split "\s+"; $my[$f[0]] = [int]$f[1] }
for ($i=0; $i -lt $ids.Count; $i++) { $r=[int]$hl[$i]; $m=[int]$my[$ids[$i]]; if ($r -ne $m) { "{0} redis={1} mysql={2}" -f $ids[$i], $r, $m } } | Select-Object -First 20
```

## 压测报告

### 8\.1 环境信息

|**项**|**值**|**来源**|
|---|---|---|
|宿主机 CPU / 内存|16 逻辑核 / 27\.8 GB|章节 1\.2|
|Docker VM CPU / 内存|16 CPU / 7\.8 GB|章节 1\.2 docker info|
|\.wslconfig|memory=8GB、processors=6、swap=0、autoMemoryReclaim=gradual、sparseVhd=true|%USERPROFILE%\\\.wslconfig|
|各容器内存上限 / 实际占用|mysql 1\.278 GiB / 1\.5 GiB；nacos 1022 MiB / 1 GiB（≈100%）；kafka 812 MiB / 1 GiB；canal\-server 695 MiB / 1\.5 GiB；redis 263 MiB / 512 MiB|docker stats|
|MySQL 镜像 tag|mysql:8\.0|章节 1\.1|
|MySQL lower\_case\_table\_names|**2**|章节 1\.3|
|Redis 镜像 tag|redis:latest，压测时为8\.8\.0|章节 1\.1|
|Kafka 镜像 tag|confluentinc/cp\-kafka:7\.6\.1|章节 1\.1|
|Nacos / Canal 镜像 tag|nacos/nacos\-server:v2\.1\.0\-slim / canal/canal\-server:v1\.1\.7|章节 1\.1|
|push\-topic / store\-topic 分区数|6 / 6（副本因子 1）|章节 1\.8|
|topic\-redpacket\-receive 分区数|3（副本因子 1）|章节 1\.8|
|三个 DLT topic|push\-topic\.DLT 6 分区、store\-topic\.DLT 6 分区、topic\-redpacket\-receive\.DLT 3 分区|章节 1\.8|
|JDK 版本|Amazon Corretto 17\.0\.13（17\.0\.13\+11\-LTS, G1GC）|java \-version|
|JMeter 版本|Apache JMeter 5\.6\.3|jmeter \-\-version|
|WebSocket 插件版本|jmeter\-websocket\-samplers\-1\.3\.2\.jar|$JMETER\_HOME\\lib\\ext|
|服务启动参数（公共）|\-XX:MaxMetaspaceSize=256m \-XX:\+UseG1GC \-Dspring\.profiles\.active=perf \-Dfile\.encoding=UTF\-8|start\-services\.ps1|
|服务堆大小|UserService / OfflineDataService / RedPacketService：\-Xms512m \-Xmx1g；RealTimeService：\-Xms512m \-Xmx1536m|start\-services\.ps1|
|实测堆占用峰值|RealTimeService 373 MB / 1536 MB；RedPacketService 476 MB / 1024 MB —— 全程远低于上限，GC 不是本轮任何结论的影响因素|metrics\_\*\.csv|
|RedPacketService：Tomcat max threads / Hikari max|200 / 20|application\-perf\.yml|
|push / store 消费者 concurrency|与配置一致（infinite\-chat\-push\-group\-0、infinite\-chat\-store\-group，各 6 分区）|application\-perf\.yml|
|topic\-redpacket\-receive 消费者 concurrency|3（3 分区 × 1 线程）|application\-perf\.yml|
|红包造数|PacketCount 6,000（\+ 4 个冒烟红包）、每包 500 份、每份 1 分、CSV 总行数 3,000,000|章节 2\.2|

> **注 1** Nacos 内存占用已达 1022 MiB / 1 GiB（≈100%）。Nacos 不在两个被测链路的数据面上（仅服务注册与配置），本轮未观察到相关异常，但若后续加压应先抬高其 `mem_limit`。
> 
> 

### 8\.2 领取红包 `POST /api/chat/redPacket/receive`

**控速档（A 组）**：固定 50 线程，逐级抬高目标 TPS，找异步落库链路跟得上的最高档。 

**开环档（B 组）**：不限速，找同步路径的极限吞吐与 Tomcat 排队拐点。

|**档**|**线程**|**目标 TPS**|**实际时长 s**|**样本数**|**成功率**|**TPS**|**平均 ms**|**P90**|**P95**|**P99**|**Max**|**服务端 ≤50ms**|**≤200ms**|**http max ms**|**Hikari active/pending 峰值**|**8103 CPU 峰值**|**lag 峰值**|**停止后归零耗时**|**领取→落库排队均值**|**有效**|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|rp\_warmup|20|100|59\.9|6,000|100%|100\.2|1\.48|2|2|3|25|100%|100%|18\.78|1 / 0|1\.90%|33|1 s|0\.04 s|是（预热档，不参与结论）|
|rp\_tps100|50|100|179\.8|18,023|100%|100\.2|1\.43|2|2|3|32|100%|100%|19\.11|2 / 0|6\.60%|26|1 s|0\.04 s|是，异步跟得上|
|rp\_tps200|50|200|179\.8|36,004|100%|200\.2|1\.27|2|2|3|27|100%|100%|18\.78|3 / 0|3\.70%|426|1 s|0\.81 s|是，异步跟得上（本轮最高的 async\_ok=True 档）|
|rp\_tps400|50|400|179\.8|71,936|100%|400|1\.24|2|2|3|22|100%|100%|10\.16|3 / 0|4\.40%|27,433|119 s（2\.0 min）|56\.83 s|是，但后台落库跟不上（async\_ok=False，最低的跟不上档）|
|rp\_tps800|50|800|179\.8|142,684|100%|793\.4|1\.29|2|2|3|25|100%|100%|21\.45|3 / 0|6\.20%|95,299|375 s（6\.3 min）|183\.01 s|是，但后台落库跟不上|
|rp\_open50|50|不限|45\.6（CSV EOF）|700,000|100%|**15,346\.50**|2\.86|4|4|5|32|100%|100%|22\.98|3 / 0|36\.50%|694,416|2,590 s（43\.2 min）|1,320\.62 s|是|
|rp\_open200|200|不限|39\.8（CSV EOF）|800,000|100%|20,078\.3 ⬅ 峰值|7\.45|12|14|19|46|100%|100%|45\.13|3 / 0|42\.70%|797,107|2,896 s（48\.3 min）|1,445\.05 s|是（满负载窗口仅约 20 s，见注 2）|
|rp\_open400|400|不限|42\.4（CSV EOF）|818,000|100%|19,310\.7 ⬇ 不增反降|13\.48|27|28|33|49|100%|100%|33\.22|3 / 0|40\.40%|814,175|2,948 s（49\.1 min）|1,484\.54 s|是（满负载窗口仅约 12 s，见注 2）|

> **注 2（开环档时长）**：三个开环档均因 CSV 读完（EOF）提前结束，而非跑满 60 s。扣掉 ramp\-up 后，真正满线程的窗口是：rp\_open50 ≈35 s、rp\_open200 ≈20 s、rp\_open400 ≈12 s。三档实际时长均 ≥30 s、线程全部启动、成功率 100%，满足章节 6\.2 的开环档有效性判定。但由于 TPS 是按全时长（含 ramp\-up）平均的，**20,078 TPS 是稳态吞吐的保守下限，真实稳态值只会更高**。
> 
> 

#### 服务端侧平均响应时间（`rp_http_sum / rp_http_count` 差值，不含压测机开销）

|**档**|**样本数**|**服务端平均 ms**|**JMeter 侧平均 ms**|**差值（压测机自身排队）**|
|---|---|---|---|---|
|rp\_tps100|18,023|1\.16|1\.43|0\.27|
|rp\_tps200|36,004|1\.13|1\.27|0\.14|
|rp\_tps400|71,936|1\.1|1\.24|0\.14|
|rp\_tps800|142,684|1\.1|1\.29|0\.19|
|rp\_open50|700,000|2\.58|2\.86|0\.28|
|rp\_open200|800,000|6\.9|7\.45|0\.55|
|rp\_open400|818,000|8\.34|13\.48|5\.14 ⬅|

**读法**：控速档和 50 线程开环档下，JMeter 与服务端的差值都在 0\.3 ms 以内，说明压测机开销可忽略、数据可信。到 400 线程时差值突然涨到 5\.14 ms —— **这部分不是服务端变慢，是 JMeter 自己在排队**。这印证了章节 8\.5「JMeter 与服务共享 CPU」的限制：400 线程开环档的 JMeter 侧响应时间已经掺入了压测机的调度开销，**该档只能用服务端侧的 8\.34 ms 作为服务端性能的依据**。

#### 一致性结论（章节 7\.2、7\.3）

|**校验项**|**值**|**结论**|
|---|---|---|
|Σ 各档 N\_ok（含冒烟/预热）|2,592,647|—|
|服务端 rp\_http\_count 差值合计|2,592,647|✅ 与 JMeter 一致|
|服务端 rp\_kafkadelay\_count 差值合计|2,592,647|✅ 每笔领取都进了 Kafka|
|mysql\_rp\_receive\_perf 差值合计|2,592,647|✅ 不丢|
|MySQL red\_packet\_receive 总行数|2,592,658|比上行多 11 行 = 冒烟红包的领取记录（不在 perf 统计口径内）|
|red\_packet 状态分布|status=0：6,011；status=1：4；status=2：6|共 6,021 个红包|
|sender\_balance / sender\_balance\_expected|996,999,996 / 996,999,996|✅ 相等|
|receivers\_balance / receivers\_balance\_expected|2,592,649 / 2,592,649|✅ 相等|
|log\_send\_sum / log\_send\_expected|−3,000,004 / −3,000,004|✅ 相等|
|log\_receive\_sum / log\_receive\_rows|2,592,649 / 2,592,649|✅ 相等（每份 1 分）|
|refund\_rows|0|✅ 24 小时内无退款|
|users\_with\_mismatch|**0**|✅ 500 个领取者余额与流水逐一对得上|
|topic\-redpacket\-receive\.DLT offset|0 / 0 / 0（3 分区）|✅ 无死信|

**钱的守恒验证**：发送者扣 3,000,004 分 = 领取者拿走 2,592,649 分 \+ 池子剩余 407,355 分。✅

### 8\.3 发送消息 `ws://``localhost:9101/ws/netty`（单聊）

|**档**|**发送者/接收者线程**|**在线连接峰值**|**send 样本数**|**成功率**|**消息/秒**|**端到端平均 ms**|**P90**|**P95**|**P99**|**Max**|**push\_ok 差值**|**push\_miss**|**push\_fail**|**push 排队均值 ms**|**store 排队均值 ms**|**push lag 峰值**|**store lag 峰值**|**归零耗时**|**8102/8101 CPU 峰值**|**有效**|
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
|msg\_warmup|20 / 20|40|5,172|100%|86\.6|2\.06|3|3|4|115|10,344 ✓|0|0|1\.57|4\.1|4|4|1 s|1\.1% / 1\.7%|是（预热档）|
|msg\_100|100 / 100|200|77,943|100%|433\.6|2\.48|4|4|7|159|155,886 ✓|0|0|2\.04|103\.92|41|218|1 s|4\.5% / 9\.4%|是，异步跟得上|
|msg\_200|200 / 200|400|155,726|100%|866\.2|2\.93|4|5|6|154|311,452 ✓|0|0|2\.5|615\.78|78|1,620|1 s|5\.1% / 10\.2%|是，异步跟得上（本轮最高的 async\_ok=True 档）|
|msg\_500|500 / 500|1,000|390,744|100%|2,172\.80|3\.23|5|6|7|55|781,488 ✓|0|0|2\.77|86,891\.63|217|203,150|153 s（2\.6 min）|9\.6% / 10\.8%|是，但后台落库跟不上（最低的跟不上档）|
|msg\_1000|1000 / 1000|2,000|782,835|100%|4,351\.30|4\.06|6|7|8|69|1,565,670 ✓|0|0|3\.65|267,043\.10|440|605,874|509 s（8\.5 min）|16\.6% / 10\.8%|是，但后台落库跟不上|

**建连情况**：5 档的 `open` 标签全部 100% 成功，`msg_1000` 的 2,000 条 WebSocket 连接建连失败数为 0，平均建连 1\.4 ms、Max 12 ms。**压测机没有成为连接数的限制**，2,000 对连接不是本机的上限。

#### 两条路径的分化（本场景的核心发现）

|**档**|**消息/秒**|**push 排队均值**|**store 排队均值**|**倍数**|
|---|---|---|---|---|
|msg\_warmup|86\.6|1\.57 ms|4\.10 ms|2\.6×|
|msg\_100|433\.6|2\.04 ms|103\.92 ms|51×|
|msg\_200|866\.2|2\.50 ms|615\.78 ms|**246×**|
|msg\_500|2,172\.80|2\.77 ms|86,891\.63 ms|31,368×|
|msg\_1000|4,351\.30|3\.65 ms|267,043\.10 ms|**73,162×**|

**读法**：`push` 排队均值从 1\.57 ms 涨到 3\.65 ms（50 倍负载只涨 2\.3 倍），`store` 排队均值从 4\.10 ms 涨到 267 秒（涨了 6\.5 万倍）。同一条消息的两条路，一条纹丝不动，一条彻底堵死。

注意 **msg\_200 已经出现了 615 ms 的 store 排队**——虽然 `async_ok=True`（停压后 1 秒归零），但这是**已经贴着上限**的信号\.

#### store 消费者实测能力（由 lag 排空速率反推）

|**档**|**生产速率**|**压测中消费速率**|**停压后排空速率**|
|---|---|---|---|
|msg\_500|2,172\.8 /s|\(390,744 − 203,150\) ÷ 180 = 1,042 /s|203,150 ÷ 153 = 1,328 /s|
|msg\_1000|4,351\.3 /s|\(782,835 − 605,874\) ÷ 180 = 983 /s|605,874 ÷ 509 = 1,190 /s|

**`infinite-chat-store-group`**** 的消费能力 ≈ 1,000 \~ 1,330 条/s。** 压测中比停压后慢约 20%，因为压测期间消费者要和 Netty 推送、Kafka 写入抢同一台机器的 CPU 和磁盘。

#### 一致性结论

|**校验项**|**值**|**结论**|
|---|---|---|
|Σ 各档 N\_sent|1,412,420|—|
|mysql\_message\_perf 差值合计|1,412,421|✅ 一致（\+1 = 两档之间跑过一次 smoke\.ps1）|
|逐档 mysql\_message\_perf 差值 vs send\_samples|77,943 / 155,726 / 5,172 / 390,744 / 782,835|✅ 五档逐条精确相等，一条不丢|
|MySQL total / distinct\_content / distinct\_id|1,418,426 / 1,418,426 / 1,418,426|✅ 三数完全相等，一条不重|
|上行 1,418,426 与 ΣN\_sent 的差额|6,004|口径差异，非丢失：校验 SQL 用 session\_id \>= 400001，把红包群会话 900001 也计入了。6,004 = 6,000 压测红包 \+ 4 冒烟红包各自的消息记录。以 snapshots\.csv 差值为准|
|逐档 push\_ok 差值|155,886 / 311,452 / 10,344 / 781,488 / 1,565,670|✅ 五档全部精确 = 2 × N\_sent|
|push\_miss / push\_fail 差值|0 / 0（全部档）|✅ 接收者时序正确，无推送失败|
|push\_delay\_count / store\_delay\_count 差值|均 = N\_sent（逐档相等）|✅ 每条消息进 push\-topic、store\-topic 各一次|
|push\-topic\.DLT / store\-topic\.DLT offset|全 0（各 6 分区）|✅ 无死信|
|分区覆盖|infinite\-chat\-push\-group\-0 6 个分区 CURRENT\-OFFSET 分别为 279,996 / 274,506 / 226,756 / 235,666 / 226,965 / 258,339，LAG 全 0|✅ 6 个分区全部有流量，最大/最小比 1\.24，sessionId 分布均匀|

### 8\.4 结论

#### 结论 1：领取红包 —— 异步落库链路是唯一瓶颈，可持续吞吐 200 TPS

- **可持续吞吐区间：200 \~ 300 TPS，报告取保守值 200 TPS。** 依据：`rp_tps200`（200 TPS，180 s，50 线程）停压后 **1 秒**归零、排队时延 0\.81 s，`async_ok=True`；`rp_tps400`（400 TPS）积压 27,433 条、归零耗时 119 s、平均排队 56\.8 s，`async_ok=False`。

- **消费者实测能力：231 \~ 276 条/s。** 依据：五个 `async_ok=False` 的档（rp\_tps400 / rp\_tps800 / rp\_open50 / rp\_open200 / rp\_open400）的「lag 峰值 ÷ 归零耗时」分别为 231 / 254 / 268 / 275 / 276 条/s，五个独立样本收敛在同一区间。**因此未补测 rp\_tps300 档**——300 已超过实测上限 276，补测结论可预判为「跟不上」，不增加信息量。与上一轮实测的 ≈250 条/s 相互印证。

- **根因：分区数即消费并发上限，不是连接池。**`topic-redpacket-receive` 仅 **3 个分区**，消费者 concurrency=3，`rp_hikari_active` 全程稳定为 **3**、`rp_hikari_pending` 全程为 **0**。Hikari 池上限 20，从未被打满，说明数据库连接不是约束。反推单条耗时 = 260 ÷ 3 ≈ 87 条/s/线程 ≈ **11\.5 ms/条**，与「每条一次跨三张表的 MySQL 事务（insert `red_packet_receive` \+ update `user_balance` \+ insert `balance_log`）」的开销吻合。

#### 结论 2：领取红包 —— 同步路径极限 20,078 TPS，排队拐点在 200 线程

- **同步路径极限吞吐：20,078 TPS**（`rp_open200`，200 线程开环，服务端平均 6\.90 ms、JMeter 侧 P99 19 ms、成功率 100%、8103 CPU 峰值 42\.7%）。

- **排队拐点：200 线程。** 50 → 200 线程，TPS 从 15,347 涨到 20,078（\+31%）；200 → 400 线程，TPS **反降到 19,311（−3\.8%）**，而平均响应从 7\.45 ms 翻倍到 13\.48 ms、P99 从 19 ms 涨到 33 ms。典型的饱和后排队特征——加线程只增加等待，不增加吞吐。

- **同步路径不是瓶颈**：即使在 20,078 TPS 下，服务端 `≤50ms` 比例仍是 100%、HTTP max 仅 45\.13 ms、CPU 峰值 42\.7%、Hikari pending 全程 0。`/receive` 只走 Redis Lua，本身极快。

- **两条路的差距：20,078 ÷ 260 ≈ 77 倍。** 同步接口能扛的量是后台落库能力的 77 倍，这正是章节 6\.2 必须用「固定线程 \+ 控速」而不是开环来测 A 组的原因。

#### 结论 3：发送消息 —— store 落库链路是瓶颈，可持续 200 对连接 / 866 msg/s

- **可持续区间：200 \~ 500 对连接（866 \~ 2,173 msg/s），报告取保守值 200 对连接 / 866 msg/s。** 依据：`msg_200`（400 条连接，866 msg/s）停压后 1 秒归零，`async_ok=True`；`msg_500`（1,000 条连接，2,173 msg/s）积压 203,150 条、归零 153 s，`async_ok=False`。

- **`infinite-chat-store-group`**** 消费能力：≈1,000 \~ 1,330 条/s**（由 msg\_500 / msg\_1000 两档的排空速率反推）。区间上限落在 866 和 2,173 之间，与阶梯结论一致。

- **push 路径远未到极限**：2,000 条连接、4,351 msg/s 下，端到端 P99 仅 **8 ms**、push 排队均值 3\.65 ms、push lag 峰值 440（瞬时）、8102 CPU 峰值 16\.6%、堆占用 373 MB / 1536 MB。`push_miss` 与 `push_fail` 全程为 0。**推送路径在本轮测试范围内没有出现任何拐点。**

- **根因同样是分区数**：`store-topic` 6 分区、`off_hikari_active` 峰值 6、`off_hikari_pending` 全程 0。反推单条耗时 = 1,100 ÷ 6 ≈ 183 条/s/线程 ≈ **5\.5 ms/条**（单表 insert message）。

#### 结论 4：两个消费组互相印证同一个结构性问题

|**消费组**|**分区数**|**Hikari active 峰值**|**pending 峰值**|**实测吞吐**|**单条耗时**|
|---|---|---|---|---|---|
|redpacket\-receive\-handler\-group|3|3|0|≈260 条/s|≈11\.5 ms（3 张表事务）|
|infinite\-chat\-store\-group|6|6|0|≈1,100 条/s|≈5\.5 ms（单表 insert）|

分区数差 2 倍 × 单条耗时差 2\.1 倍 = 吞吐差 4\.2 倍（1,100 ÷ 260 = 4\.2）。**两组数据完全自洽，共同指向同一个结论：本系统异步链路的吞吐上限 = 分区数 × \(1 / 单条事务耗时\)，与数据库连接池无关（pending 全程为 0）。**

#### 改进方向（仅记录，改系统不在本方案范围）

按预期收益排序：

1. **提高分区数与消费者 concurrency**。`topic-redpacket-receive` 从 3 提到 6，理论吞吐可翻倍到 ≈520 条/s；`store-topic` 同理。成本最低、风险最小，但受单机 CPU 与 MySQL 写入能力封顶。

2. **消费端改批量落库**（`batch listener` \+ `insert ... values (...),(...)` 或 `rewriteBatchedStatements=true`）。单条 11\.5 ms 里大部分是事务提交与网络往返，批量提交通常能带来数倍提升，且不依赖加分区。

3. **红包消费端合并事务**。目前一条领取事件开一个跨三张表的事务，可考虑把 `balance_log` 改异步或批量写。

以上任一项改动后，需按本方案重跑 A 组阶梯与消息阶梯复测。

## 8\.5 结论适用范围

- 所有数字在 **单实例、压测机与被测系统同机、中间件经 Docker Desktop（WSL2）端口转发** 的条件下取得，不能外推到独立部署或多实例。

- **Docker VM 实际获得 16 CPU / 7\.8 GB**。复现时须按实测值配置，否则中间件可用 CPU 减少，异步链路吞吐会低于本轮结果。

- RealTimeService 没有跨节点推送能力，消息场景结论仅对单实例成立。

- 群聊消息场景不在本轮范围；红包场景造数使用群聊红包，但被测接口只有 `/receive`。

- JMeter 与服务共享 CPU。控速档与 50 线程开环档下，JMeter 与服务端的响应时间差值 ≤0\.3 ms，数据可信；**400 线程开环档的差值达 5\.14 ms，该档的 JMeter 侧响应时间已掺入压测机调度开销，只应采用服务端侧的 8\.34 ms**。

- 各档响应时间只用于纵向比较，不作为生产环境 SLA 依据。

- 红包 Redis 数据 24 小时过期，本轮结果基于造数后 24 小时内完成的压测。

- **本轮未测到的上限**：WebSocket 连接数（2,000 对连接下建连失败 0、CPU 16\.6%，未见拐点）；push 消费组吞吐（4,351 msg/s 下 lag 峰值仅 440）。若需这两个数字，须单独设计测试，并预先隔离 store 链路的积压干扰。







# [重跑操作手册](https://q0kyes8lnt5.feishu.cn/wiki/NIY0wmEUeijd7rkzTO3cwoymnrf)

