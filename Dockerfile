# ---------- Build stage ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy wrapper + POM trước để tận dụng cache layer dependency
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Đảm bảo mvnw chạy được trên Linux (loại CRLF nếu có) và có quyền thực thi
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -B dependency:go-offline

COPY src ./src
RUN ./mvnw -B package -DskipTests

# ---------- Run stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/career-map-server-0.0.1.jar app.jar
EXPOSE 8080

# Render free plan: container bị TẮT sau ~15 phút không có request, lần sau
# phải khởi động lại từ đầu (~60–90s). Các flag dưới cắt thời gian JVM khởi
# động, ưu tiên "dựng được nhanh" hơn "chạy nhanh nhất":
#   -XX:TieredStopAtLevel=1  chỉ dùng JIT C1, bỏ qua việc biên dịch C2 lúc
#                             boot. App lượt người chơi rất nhẹ nên không bị
#                             ảnh hưởng.
#   -XX:+UseSerialGC         GC nối tiếp, khởi tạo ít luồng hơn.
#   -Xms256m -Xmx512m        cấp sẵn heap để không phải resize lúc boot.
ENTRYPOINT ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xms256m", "-Xmx512m", "-jar", "/app/app.jar"]