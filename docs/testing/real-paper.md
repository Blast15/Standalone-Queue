# Paper 1.21.11–26.3 — network / recovery / load

Cập nhật 11/10/2026. Phạm vi mục tiêu do chủ dự án xác nhận: **1.21.11 đến 26.3**. Chủ dự án đã đồng ý Minecraft EULA cho các runtime staging cô lập. Đây là kiểm tra server Paper thật và socket loopback; chưa phải nghiệm thu production online-mode/LuckPerms.

## Ma trận đã thực nghiệm

| Minecraft / Paper | Build | Channel | Java runtime | Offline network / recovery |
|---|---:|---|---|---|
| 1.21.11 | 132 | STABLE | Temurin 21.0.12.1+1 | 12 nhóm kiểm tra |
| 26.1.1 | 29 | ALPHA | Corretto 25.0.4.1+10 | 12 nhóm kiểm tra |
| 26.1.2 | 74 | STABLE | Corretto 25.0.4.1+10 | 12 nhóm kiểm tra |
| 26.2 | 133 | STABLE | Corretto 25.0.4.1+10 | 12 nhóm kiểm tra |
| 26.3 | 170 | BETA | Corretto 25.0.4.1+10 | 12 nhóm kiểm tra |

[JSON bằng chứng](evidence/2.0.0-paper-matrix.json) chứa kết quả từng ca, build/checksum Paper và hash JAR plugin. Metadata/API chính thức không cung cấp Paper STABLE cho 26.1.1/26.3 ở thời điểm kiểm tra; hỗ trợ hai build này là **thử nghiệm**, không suy diễn mọi build trong cả dải đã được kiểm tra. Paper upstream có vòng đời hỗ trợ riêng với cam kết tương thích của plugin.

Mỗi runtime chạy cùng một JAR 2.0.0 biên dịch với API tối thiểu 1.21.11, bytecode Java 21 (major 65). 144 unit và 14 Paper API event integration đạt trên Java 21 và Java 25. Probe cũng build thành công. Gradle 9.4.1 `classes jar` đạt qua init script dùng Maven cache local; chưa có Gradle wrapper, Maven là build chuẩn của CI. Descriptor Maven/Gradle đều hiển thị 2.0.0.

## Các nhóm kiểm tra

1. Boot Paper, load plugin và probe đúng version 2.0.0; snapshot capacity trống.
2. Người giữ slot vào PLAY/JOIN; hai người chờ nhận kick queue, FIFO đúng; người thứ ba cùng IP bị quota từ chối.
3. QUIT tạo lease độc quyền. Người thứ hai reconnect không lấy slot của người thứ nhất; hai lượt lần lượt JOIN thành công; admission/lease được dọn.
4. Plugin test từ chối ở cuối pre-login: terminal cancellation giải phóng admission.
5. Plugin test từ chối ở PlayerLogin: terminal failure giải phóng admission.
6. Ban và whitelist gốc của server vẫn từ chối; queue không override các lý do này.
7. Pause từ chối admission mới; resume tiếp tục hoạt động.
8. Cố tình giữ authentication thread 6.5 giây với deadline 5 giây; người chờ khác không chiếm slot còn in-flight. Login muộn bị từ chối, sau đó mới chuyển lease. Deadline thông thường 30 giây, chỉ giảm cho ca này.
9. Reload capacity không hợp lệ giữ generation cũ; reload hợp lệ đổi capacity; trả cấu hình về trạng thái ban đầu.
10. Graceful stop/start giữ WAITING, cấp lease mới theo capacity hiện tại; reconnect được JOIN.
11. SIGKILL sau khi xác nhận snapshot có lượt chờ đã ghi xong; boot lại phục hồi WAITING, không phục hồi admission/token/lease cũ.
12. Ghi snapshot hỏng khi server đã dừng; boot fallback memory-only, client vẫn JOIN/QUIT được; shutdown giữ nguyên file lỗi để điều tra.

Các snapshot đối chiếu `online + leases + inFlight <= capacity` khi không có bypass. Client PLAY phải được xác nhận thêm bằng Paper JOIN/counter và state snapshot. Test không đo gameplay/rendering hoặc xác thực Microsoft/Mojang.

Trong một lượt chạy song song, 26.1.1 đã bị timeout ở deadline 5 giây quá ngắn cho cold client configuration trên host dùng chung. Harness được sửa: 30 giây cho login thường, 5 giây chỉ tại fixture delay; chạy lại đầy đủ. Lỗi client ban đầu ở 26.3 do whitelist mặc định và teleport acknowledgement có thêm position/rotation đã được xử lý trong cấu hình/client staging; không thay đổi chính sách whitelist của plugin.

## Tải network thật 500 / 50

Paper 26.2 build 133, Java 25, heap tối đa 1 GiB, offline-mode, view/simulation distance 2:

- 50 socket client riêng biệt đi qua Paper JOIN và giữ online.
- 500 tên/UUID offline khác nhau bị queue kick và trở thành WAITING; snapshot xác nhận đủ 500, không có admission/lease thừa.
- Lượt thứ 501 bị từ chối và queue vẫn bounded ở 500.
- Đóng một client online: tạo đúng một lease; `Queued0` reconnect, online trở lại 50 và queue còn 499.
- Thời gian toàn bài đo gồm startup: 35.28 giây trên host dùng chung. Đây là kiểm tra tính đúng, **không phải throughput/p95/p99 hay SLO production**. Persistence bật trong lifecycle/recovery suite; bài tải 500/50 dùng persistence mặc định tắt.

Nguồn IP là các địa chỉ `127.x.x.x`, không phải phân bố mạng/NAT Internet thật. Các client queue bị kick không giữ 500 socket online đồng thời.

## Tái chạy

Java 21 để build, Java 21 cho runtime 1.21.11, Java 25 cho 26.x; Node 24, Python 3 standard library và Maven. Không cài probe trên production.

```bash
mvn -B clean install
mvn -B clean verify -f staging-probe/pom.xml
npm ci --ignore-scripts --no-audit --no-fund --prefix scripts/staging
node scripts/staging/check-client.mjs
# Chỉ dùng --accept-eula sau khi chính chủ môi trường đồng ý Minecraft EULA.
python3 scripts/staging/prepare.py --version 1.21.11 --prepare-only --accept-eula
python3 scripts/staging/smoke.py --version 1.21.11 --java /path/to/java21/bin/java
python3 scripts/staging/prepare.py --version 26.3 --allow-unstable --prepare-only --accept-eula
python3 scripts/staging/smoke.py --version 26.3 --java /path/to/java25/bin/java
# Bài tải dùng runtime riêng, mới hoàn toàn.
python3 scripts/staging/prepare.py --version 26.2 --runtime .staging-work/load-26.2 --prepare-only --accept-eula
python3 scripts/staging/smoke.py --version 26.2 --runtime .staging-work/load-26.2 --load --java /path/to/java25/bin/java
```

Preparer chỉ nhận directory trống hoặc có marker staging, bind `127.0.0.1:25576`, tắt RCON/query. Nó xác minh SHA-256 Paper và Mojang bootstrap từ `META-INF/download-context`. `--allow-unstable` cần chủ động cho bản ALPHA/BETA; không giả định có STABLE. Smoke từ chối config đã tồn tại để không vô tình sửa trạng thái test trước; muốn chạy lại, lưu bằng chứng và dùng một runtime staging mới, hoặc dọn riêng data/config staging khi mọi process đã dừng. Không trỏ runtime vào production.

Java và client phải được spawn trong cùng network namespace; `smoke.py` làm việc này. Log/result nằm trong runtime đã gitignore. CI chạy đủ 5 runtime độc lập và bài tải 26.2; upload log/result/source metadata, release job chờ build + Java 25 + Paper matrix hoàn tất.

`minecraft-protocol` 1.68.0 hỗ trợ 1.21.11/26.1.x. Với 26.2/26.3, `raw_client.py` lấy protocol number từ `version.json` Mojang và packet registration từ **đúng patched server** bằng `javap`, ghi `raw-protocol-map.json` cùng hash JAR. Nó xử lý compression, configuration, known packs, teleport acknowledgement và keepalive; bỏ qua world rendering/gameplay. Không relabel 26.1 thành protocol mới. Relative teleport chưa được hỗ trợ và sẽ làm test fail rõ ràng.

## Gate production còn mở

- Online-mode với tài khoản Minecraft được xác thực thật; UUID/auth/session không được mock trong kết quả này.
- LuckPerms binary/storage/event bus thật, priority/bypass/context và revoke trong downtime/reload. Unit provider doubles đã qua nhưng không thay thế backend thật. Download API trả 502; endpoint metadata/download trả 403 trong môi trường này. Build source đã thử nhưng Gradle dependency network bị chặn; không coi là đã chạy LuckPerms.
- SIGKILL được thực hiện sau snapshot đã hoàn tất; chưa thử power-loss hoặc kill giữa filesystem syscall. Các atomic-write failure cases có unit tests riêng.
- Rehearsal backup/rollback trên filesystem/server mục tiêu, soak/canary, heap/GC, p95/p99, NAT false-positive và ngưỡng vận hành.
- Paper 26.3 STABLE khi upstream có bản tương ứng; retest trước khi bỏ nhãn thử nghiệm.

Không tag/merge để tuyên bố production-ready chỉ vì ma trận offline xanh. Java runtime staging có cảnh báo DNS khi gọi dịch vụ public-key/version của Mojang/Paper; đó cũng là lý do không suy diễn xác thực online từ test offline. Những lỗi snapshot chủ động tạo có SEVERE dự kiến và được kiểm tra preservation.

## Nguồn chính thức

- Paper version/build metadata: https://fill.papermc.io/v3/projects/paper
- Java requirements: https://docs.papermc.io/paper/getting-started/
- Paper API setup/versioning: https://docs.papermc.io/paper/dev/project-setup/
- EULA: https://www.minecraft.net/en-us/eula
- Client protocol library: https://github.com/PrismarineJS/node-minecraft-protocol
- LuckPerms source: https://github.com/LuckPerms/LuckPerms
- LuckPerms metadata API: https://github.com/LuckPerms/metadata-api
