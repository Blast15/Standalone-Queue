# StandaloneQueue

[![Build](https://github.com/BlastSama/StandaloneQueue/actions/workflows/build.yml/badge.svg)](https://github.com/BlastSama/StandaloneQueue/actions/workflows/build.yml)
[![Paper](https://img.shields.io/badge/Paper-1.21%2B-00a86b)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Plugin hàng chờ độc lập cho máy chủ Minecraft Paper, **hoàn toàn không cần máy chủ trung gian (Proxy / BungeeCord / Velocity)**.

**Author:** BlastSama  
**Version:** 1.0.1
**Supported API:** Paper 1.21+

StandaloneQueue keeps a full Paper server usable without a proxy: players receive a fair, priority-aware queue while administrators get bounded admission controls for join-bot bursts.

## Documentation

- [Installation and first setup](docs/wiki/Installation.md)
- [Queue and anti-joinbot configuration](docs/wiki/Configuration.md)
- [Operations runbook](docs/wiki/Operations.md)
- [Security model and limitations](docs/wiki/Security.md)
- [Contributing](CONTRIBUTING.md)

---

## 🌟 Tính Năng Nổi Bật

1. **Không cần Proxy**: Hoạt động trực tiếp trên máy chủ Paper đơn lẻ thông qua can thiệp `AsyncPlayerPreLoginEvent`.
2. **Cơ chế Duy trì Hàng Chờ (Heartbeat Keep-Alive 5 phút)**:
   - Khi server đầy, người chơi kết nối sẽ được thêm vào hàng chờ và nhận màn hình thông báo vị trí kèm thời gian đếm ngược.
   - Để giữ vị trí trong hàng chờ, người chơi chỉ cần bấm **"Kết nối lại" (Reconnect)** trong vòng **5 phút (300 giây)**.
   - Mỗi lần bấm kết nối lại, hệ thống sẽ cập nhật lại thời gian sống (reset bộ đếm 5 phút) và hiển thị vị trí mới nhất đã tiến lên.
   - Nếu quá 5 phút người chơi không kết nối lại, hệ thống tự động loại khỏi hàng chờ để nhường chỗ cho người sau.
3. **Cơ chế Giữ Chỗ Chống Cướp Lượt (Slot Reservation)**:
   - Khi có người trong server thoát ra, vị trí trống sẽ được **cấp quyền giữ chỗ riêng (Reservation)** cho người đứng đầu hàng chờ (#1) trong 60 giây (tùy chỉnh).
   - Những người đứng sau (#2, #3,...) hay người mới dù có spam Reconnect trước cũng không thể cướp slot này.
   - Khi người #1 kết nối lại trong thời gian giữ chỗ, họ sẽ được đưa thẳng vào máy chủ thành công.
4. **Xử lý Bất đồng bộ & An Toàn Tuyệt Đối (Thread-Safety)**:
   - Sử dụng `ReentrantReadWriteLock` ngăn ngừa 100% tình trạng Race Condition khi nhiều người kết nối cùng lúc.
   - Tích hợp kiểm soát In-Flight Logins (chống overfilling khi nhiều người cùng lúc qua màn hình đăng nhập).
   - Tích hợp Rate-Limit chống bot spam connection.
5. **Bảo vệ Join-Bot cho máy chủ crack**:
   - Giới hạn tốc độ kết nối theo IP và toàn server trong mỗi cửa sổ thời gian.
   - Giới hạn số tài khoản cùng IP được giữ trong hàng chờ.
   - Giới hạn số IP được theo dõi để tránh tấn công làm phình bộ nhớ.
   - Có thể bật/tắt runtime bằng `/queue antibot on|off|status`.
   - Nên kết hợp firewall hoặc lớp chống DDoS khi bị flood kết nối thật.
6. **Hệ Thống Phân Hạng Ưu Tiên (Priority & Bypass)**:
   - Quyền `queue.bypass`: Bỏ qua hàng chờ, vào thẳng ngay cả khi đầy.
   - Quyền `queue.priority.vip`, `queue.priority.mvp`: Được xếp trước người chơi thông thường.
6. **Giao Diện MiniMessage Hiện Đại**:
   - Hỗ trợ màu Gradient, Hex, Bold, Tag MiniMessage trên màn hình ngắt kết nối.

---

## ⚙️ Cấu Trúc Lệnh & Quyền Hạn

### Lệnh Quản Trị:
| Lệnh | Mô tả | Quyền hạn |
|---|---|---|
| `/queue status` | Xem thông số server, số slot online, override và số người đang xếp hàng | `queue.admin` |
| `/queue list` | Xem danh sách chi tiết từng người trong hàng chờ, thứ tự, ping cuối | `queue.admin` |
| `/queue kick <tên>` | Đá một người chơi ra khỏi hàng chờ | `queue.admin` |
| `/queue clear` | Xóa sạch toàn bộ hàng chờ | `queue.admin` |
| `/queue reload` | Tải lại cấu hình `config.yml` | `queue.admin` |
| `/queue antibot on\|off\|status` | Bật, tắt hoặc xem trạng thái bảo vệ join-bot | `queue.admin` |

### Quyền Hạn (Permissions):
- `queue.admin`: Toàn quyền quản trị hàng chờ (mặc định: OP).
- `queue.bypass`: Bỏ qua hàng chờ và vào thẳng server.
- `queue.priority.vip`: Ưu tiên hàng chờ VIP (Weight: 10).
- `queue.priority.mvp`: Ưu tiên hàng chờ MVP (Weight: 20).
- `queue.priority.staff`: Ưu tiên hàng chờ Staff (Weight: 50).

---

## 🛠️ Hướng Dẫn Biên Dịch & Cài Đặt

### Biên dịch với Maven:
```bash
mvn clean package
```
File JAR sẽ nằm tại `target/StandaloneQueue-1.0.1.jar`.

### Biên dịch với Gradle:
```bash
gradle build
```
File JAR sẽ nằm tại `build/libs/StandaloneQueue-1.0.1.jar`.

---

## 📋 Cấu Hình Mẫu (`config.yml`)

```yaml
queue:
  enabled: true
  max-players-override: -1       # Đặt -1 để lấy theo server.properties, hoặc số lượng tùy chỉnh
  keep-alive-seconds: 300       # 5 phút để duy trì vị trí
  slot-reservation-seconds: 60  # Thời gian giữ slot cho người đứng đầu
  rate-limit-seconds: 2         # Chống spam bấm reconnect
  max-queue-size: 500           # Sức chứa tối đa của hàng chờ
  anti-bot:
    enabled: true               # Bật bảo vệ join-bot mặc định
    window-seconds: 10          # Cửa sổ tính giới hạn
    max-attempts-per-ip: 20     # Số lần thử tối đa từ một IP/cửa sổ
    max-queued-per-ip: 5        # Số bot tối đa cùng IP trong queue
    global-attempts-per-window: 100 # Tổng số lần thử toàn server/cửa sổ
```

### Vận hành khi bị tấn công

Có thể tắt tạm thời bằng `/queue antibot off` để xử lý sự cố hoặc bật lại bằng `/queue antibot on`. Trạng thái runtime không ghi đè file cấu hình; `/queue reload` sẽ quay về giá trị trong `config.yml`.
