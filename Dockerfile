# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy wrapper + POM từ thư mục server/ lên gốc build context
COPY server/.mvn .mvn
COPY server/mvnw mvnw
COPY server/pom.xml pom.xml
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

COPY server/src ./src
RUN ./mvnw -B package -DskipTests

# ---------- Run stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/career-map-server-0.0.1.jar app.jar
EXPOSE 8080

# Render free plan: container bị TẮT sau ~15 phút không có request,
# lần sau phải khởi động lại từ đầu (~60–90s). Các flag dưới cắt thời
# gian JVM khởi động, ưu tiên "dựng được nhanh" hơn "chạy nhanh nhất":
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xms256m", "-Xmx512m", "-jar", "/app/app.jar"]
