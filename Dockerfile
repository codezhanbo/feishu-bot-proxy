# 多阶段构建：JDK 8 打包，精简 JRE 8 运行。
# 构建与运行阶段都用 eclipse-temurin（glibc 基础镜像）；postgresql 驱动是纯 Java，无 JNI 依赖。

# ---------- 阶段一：构建 ----------
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /app
# 先只拷 pom.xml，让依赖下载层能被 Docker 缓存
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

# ---------- 阶段二：运行 ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /app/target/feishu-bot-proxy-1.0.0.jar app.jar
EXPOSE 8080
# 显式收紧 JVM 内存上限：Render 用 cgroup 限制容器内存，但 Java 8 读不到 cgroup v2 的限制，
# 默认会按宿主机内存给堆设一个巨大上限，进程实际占满容器后被内核 OOM 杀掉（就是「exceeded memory limit」）。
# 堆 + 元空间 + 线程栈 + 代码缓存 + 连接池原生内存要一起压进容器配额内，不能只限制堆。
ENTRYPOINT ["java", \
    "-Xms128m", "-Xmx256m", \
    "-XX:MaxMetaspaceSize=128m", \
    "-Xss512k", \
    "-XX:MaxDirectMemorySize=32m", \
    "-jar", "app.jar"]
