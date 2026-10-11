# Real Paper staging — chuẩn bị môi trường

Cập nhật 11/10/2026. Mục tiêu: đóng các gate Paper thật còn chờ ở PR #4 trước khi xem xét production. Đây là **bản ghi chuẩn bị**, không phải kết quả gameplay/admission đã đạt.

## Bằng chứng đã thực hiện

- Tải Paper 1.21.4 stable build 232 từ API v3 chính thức.
- Xác minh SHA-256 `5ee4f542f628a14c644410b08c94ea42e772ef4d29fe92973636b6813d4eaffc` theo metadata của Paper.
- Tải Mojang bootstrap server từ URL trong `META-INF/download-context`, xác minh checksum được Paper đóng gói.
- Chạy bootstrap bằng Java 21.0.12.1+1: Paper patches/remapping thành công; server dừng tại `You need to agree to the EULA in order to run the server` với `eula=false`. Không gameplay test nào được coi là đã chạy.
- Cài test-only `minecraft-protocol` 1.68.0 (lockfile); client RPC có source loopback guard, giữ Minecraft version 1.21.4, không yêu cầu/lưu credentials Microsoft.
- `node --check`, Python compilation và client loopback guard check thành công. Preparer `--prepare-only` thành công; đường chạy bình thường bị guard từ chối khi EULA chưa được chấp thuận.
- LuckPerms download API trả HTTP 502, trang/download endpoint không truy cập được trong phiên. Chưa có binary/backend LuckPerms thật và chưa gỡ gate này.

## Tái tạo và chạy sau khi chấp thuận EULA

Yêu cầu Java 21, Maven, Python 3 standard library và Node >=22. Nguồn runtime/bootstrap phải truy cập được qua mạng; preparer kiểm tra SHA-256 và không tự chấp thuận EULA.

```bash
mvn -B clean install
mvn -B clean verify -f staging-probe/pom.xml
npm ci --ignore-scripts --no-audit --no-fund --prefix scripts/staging
python3 scripts/staging/prepare.py --prepare-only
```

Runtime mặc định `.staging-work/paper-1.21.4` (gitignored), không sử dụng world/plugin directory production. Bản đầu bind `127.0.0.1:25576`, offline-mode trong môi trường cô lập, capacity 1 và view-distance 2. Nếu runtime đã được preparer đánh dấu, world/settings/state được giữ cho test restart. Không trỏ `--runtime` vào directory production.

Sau khi chủ máy chủ đã đọc và xác nhận [Minecraft EULA](https://www.minecraft.net/en-us/eula), có thể chủ động chạy:

```bash
python3 scripts/staging/prepare.py --accept-eula
```

Flag này ghi `eula=true` cho runtime staging. Không chạy flag thay người dùng khi chưa có xác nhận. Để giữ server/client cùng network namespace, orchestrator cần spawn cả preparer/Java và `node scripts/staging/clients.mjs` trong cùng process environment.

Client RPC nhận từng dòng JSON qua stdin; stdout chỉ có JSON event:

```json
{"action":"connect","id":"a1","name":"StagingA","port":25576,"localAddress":"127.0.0.2"}
{"action":"close","id":"a1"}
{"action":"shutdown"}
```

Source 127.0.0.2/127.0.0.3 cho phép tạo nhóm NAT/bot riêng trên TCP loopback. Điều này kiểm tra budget/IP nhìn thấy bởi Paper, không mô phỏng routing/latency của một NAT production. Event `play` là packet login game; phải đối chiếu trace `SQStagingProbe` JOIN và snapshot queue/capacity, không chỉ dựa vào kết nối TCP thành công. Companion probe chỉ dùng cho staging và phải được loại khỏi production.

## Gate còn phải chạy

Giữ ma trận [phase 2](phase-2-staging.md), [phase 3](phase-3-staging.md) và [recovery 2.0.0](../releases/2.0.0.md):

1. Normal/full/reservation, simultaneous/duplicate connection, exact lifecycle close, injected late pre-login/login denial và slow handshake.
2. Reload valid/invalid/priority/capacity, pause/resume/move/permissions, ban/whitelist, console provenance.
3. 500 queued/50 attempts, NAT 50/bot 200, protected reconnect budget, ETA/metrics/heap/latency.
4. Persistence on: graceful stop, process kill quanh flush, checksum/schema/corruption, changed rights/capacity và snapshot rollback.
5. LuckPerms backend thật, revoke/explicit bypass denial; online authentication thật cần account được chủ sở hữu cung cấp hoặc client đã đăng nhập. Fixture session service chỉ có thể chứng minh pipeline, không được gọi là xác thực Mojang thật.
6. Kiểm tra phiên bản/config/plugin stack production cụ thể và canary. Chưa biết host/version/credentials production nên chưa triển khai lên máy chủ thật.

Chưa merge/tag/release chỉ dựa trên trạng thái chuẩn bị này.

## Nguồn

- Paper downloads API: https://docs.papermc.io/misc/downloads-service/
- Metadata build: https://fill.papermc.io/v3/projects/paper/versions/1.21.4/builds
- Minecraft EULA: https://www.minecraft.net/en-us/eula
- Node protocol source/API: https://github.com/PrismarineJS/node-minecraft-protocol
- LuckPerms source/build: https://github.com/LuckPerms/LuckPerms
