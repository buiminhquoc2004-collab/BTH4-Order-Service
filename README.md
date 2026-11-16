# Bài TH4 – Order Management Service

Dịch vụ quản lý đơn hàng theo SOA, Java 21 / Spring Boot 4.1.1 / MySQL 8.

## Kiến trúc và phạm vi

| Dịch vụ | Cổng | Dữ liệu |
|---|---:|---|
| BTH2 – xác thực JWT | 8080 | Database của BTH2 |
| BTH3 – Product Service | 8081 | BTH3_PRODUCT |
| BTH4 – Order Service | 8082 | BTH4_ORDER |

BTH4 gọi `GET /auth` của BTH2 để xác thực Bearer token, giống cách BTH3 đang dùng. Khi đặt hàng, BTH4 gọi `GET /products/{id}` của BTH3, chuyển tiếp token, kiểm tra số lượng và lấy giá. BTH4 chỉ truy cập database của mình; `product_id` là tham chiếu logic, không có khóa ngoại sang database sản phẩm.

Dòng “tạo, xem, cập nhật, xóa sản phẩm” trong đề được thực hiện ở BTH3. BTH4 cung cấp CRUD đơn hàng và mặt hàng trong đơn, cùng cập nhật trạng thái đơn.

## Chạy trong Spring Tool Suite / Eclipse

1. Cài **JDK 21** (không chỉ JRE), MySQL 8, STS/Eclipse có Maven.
2. MySQL Workbench: mở và chạy `database/OrderDB.sql`.
3. Giải nén, chọn **File → Import → Maven → Existing Maven Projects**.
4. Root Directory: chọn thư mục `OrderManagement` có `pom.xml` → Finish.
5. Chuột phải project → **Maven → Update Project**; kiểm tra JRE System Library dùng JavaSE-21.
6. **Run → Run Configurations → Spring Boot App** (hoặc Java Application), chọn `OrderManagementApplication`.
7. Tab **Environment**, thêm:

| Biến | Giá trị |
|---|---|
| `ORDER_DB_USER` | `root` hoặc tài khoản MySQL của bạn |
| `ORDER_DB_PASSWORD` | Mật khẩu MySQL của bạn |
| `ORDER_DB_URL` | Tùy chọn, mặc định `jdbc:mysql://localhost:3306/BTH4_ORDER?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8` |
| `AUTH_SERVICE_URL` | Tùy chọn, mặc định `http://localhost:8080` |
| `PRODUCT_SERVICE_URL` | Tùy chọn, mặc định `http://localhost:8081` |

8. Chạy BTH2, BTH3 rồi chạy BTH4. Console BTH4 hiển thị cổng **8082**.
9. Đăng nhập ở BTH2, lấy token; tạo sản phẩm ở BTH3 có tồn kho đủ để đặt hàng.

Không lưu mật khẩu hoặc token thật trong mã nguồn. `application.properties` đọc biến môi trường, không chứa mật khẩu cố định. Chỉ BTH2 quản lý khóa JWT; BTH4 không cần `JWT_SECRET`.

## Chạy bằng Maven

Trong thư mục chứa `pom.xml`, sau khi đặt biến môi trường:

```bash
mvn clean verify
mvn spring-boot:run
```

Hoặc sau khi build:

```bash
java -jar target/order-management-0.0.1-SNAPSHOT.jar
```

## API

Mọi request cần `Authorization: Bearer <token BTH2>`.

| Method | Đường dẫn | Chức năng |
|---|---|---|
| GET | `/orders` | Danh sách đơn |
| GET | `/orders/{id}` | Chi tiết và các mặt hàng |
| POST | `/orders` | Tạo đơn và kiểm tra tồn kho |
| PUT | `/orders/{id}` | Đổi trạng thái |
| DELETE | `/orders/{id}` | Xóa đơn pending và các mặt hàng |
| GET | `/order_items` | Danh sách mặt hàng trong đơn |
| GET | `/order_items/{id}` | Chi tiết mặt hàng |
| POST | `/order_items` | Thêm sản phẩm vào đơn |
| PUT | `/order_items/{id}` | Sửa sản phẩm/số lượng |
| DELETE | `/order_items/{id}` | Xóa mặt hàng và tính lại tổng |

Tạo đơn, `POST /orders`:

```json
{"customerName":"Nguyen Van A","customerEmail":"a@example.com","items":[{"productId":1,"quantity":2}]}
```

Đổi trạng thái, `PUT /orders/1`:

```json
{"status":"completed"}
```

Thêm mặt hàng, `POST /order_items`:

```json
{"orderId":1,"productId":1,"quantity":1}
```

Sửa mặt hàng, `PUT /order_items/1`:

```json
{"productId":1,"quantity":3}
```

- Trạng thái: `pending → completed` hoặc `pending → cancelled`; gửi lại cùng trạng thái được phép.
- Chỉ đơn pending được thêm/sửa/xóa mặt hàng hoặc xóa đơn.
- Số lượng phải là số nguyên dương; gộp số lượng cùng product khi kiểm tra kho.
- Giá và tên sản phẩm được chụp lại vào đơn. `totalAmount = Σ(quantity × unitPrice)`.
- Tạo đơn nằm trong transaction: lỗi một sản phẩm thì không lưu đơn dở dang.
- Các thao tác sửa một đơn khóa dòng đơn trong database để tránh ghi đè tổng tiền khi có nhiều request.
- Lỗi: 400 dữ liệu sai/sản phẩm không tồn tại; 401 token sai/thiếu; 404 không có đơn/item; 409 thiếu tồn kho hoặc trạng thái không cho phép; 502 Product Service lỗi; 503 Auth Service không truy cập được.

## Demo với Postman

Import `postman/BTH4_OrderManagement.postman_collection.json`. Điền collection variables `token`, `productId` (ID có thật ở BTH3), `quantity`. `baseUrl` mặc định `http://localhost:8082`.

Chạy từng request theo thứ tự. Request tạo đơn tự lưu `orderId` và `itemId`; request thêm item tự lưu `addedItemId`. Sau bước xóa đơn, tạo đơn mới rồi demo hoàn thành hoặc hủy. Bộ request có kiểm tra HTTP status cơ bản. Test thiếu kho dùng quantity 2147483647; chọn sản phẩm có tồn kho nhỏ hơn số này.

## Kiểm thử tự động

Đã xác minh 12/12 ca kiểm thử thành công ngày 16/11/2026.

`mvn clean verify` chạy `OrderApiTest`: gửi HTTP thật đến BTH4 với database H2 riêng cho test. BTH2/BTH3 được giả lập bằng HTTP server để test không phụ thuộc mật khẩu, MySQL hay dịch vụ bên ngoài. Suite kiểm tra xác thực, tổng tiền, validation, tồn kho, sản phẩm trùng, lỗi upstream, CRUD item, trạng thái và xóa đơn.

H2 chỉ dùng trong test; môi trường chạy thật vẫn là MySQL. Kết quả test không thay thế việc chạy thử ba dịch vụ thật trên máy bạn.

## Giới hạn bài thực hành

BTH4 **kiểm tra** tồn kho, chưa giữ hoặc trừ kho ở BTH3. Nhiều đơn khác nhau vẫn có thể cùng vượt tồn kho; muốn bán hàng thật cần API giữ/trừ kho nguyên tử ở BTH3 và cơ chế bù giao dịch. Các endpoint hiện yêu cầu đăng nhập, chưa phân quyền đơn theo từng chủ tài khoản.

## Nộp qua GitHub

Tạo repository `BTH4-Order-Service`, rồi đưa toàn bộ nội dung thư mục này lên repository, để `pom.xml` nằm ở thư mục gốc. Không đưa `target/`, mật khẩu hay token lên GitHub.

```bash
git init
git add .
git commit -m "Complete BTH4 Order Management service"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/BTH4-Order-Service.git
git push -u origin main
```

Nộp URL repository trên hệ thống lớp. Nếu repository đã tồn tại, clone về rồi chép mã nguồn vào, không dùng force push.
