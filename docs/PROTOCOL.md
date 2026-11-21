# Протокол Slate

Транспорт — WebSocket, текстовые кадры с JSON-объектом. Первое поле —
`type`. Все сообщения клиента, кроме `AUTH_DEVICE`, `LOGIN`, `REGISTER`,
требуют авторизованной сессии; иначе сервер отвечает `ERROR` и не выполняет
операцию. Состояние (`userId`, роль, активная доска) хранится на сервере, клиент
не может его подделать полями сообщения.

Реализация: `java-collab/server/.../CollaborationServer.java`,
`src/main/java/org/example/canvasproject/collab/CollaborationClient.java`.

## Клиент → сервер

| Тип | Поля | Права / примечание |
|---|---|---|
| `AUTH_DEVICE` | `deviceKey`, `deviceName` | ключ 32–128 символов; при `ALLOW_NEW_DEVICES=0` неизвестный ключ отклоняется |
| `REGISTER` | `username`, `email`, `password` | только при `ALLOW_PASSWORD_AUTH=1` (устарело) |
| `LOGIN` | `username`, `password` | только при `ALLOW_PASSWORD_AUTH=1` (устарело) |
| `LOGOUT` | — | удаляет токен, выводит из комнаты |
| `UPDATE_PROFILE` | `username` (1–32), `color` (`#RRGGBB`), `avatar` (data URL ≤ 1,5 МБ) | только свой профиль |
| `CREATE_CANVAS` | `name`, `canvasWidth`/`width`, `canvasHeight`/`height` | размер зажимается: 400–4000 × 300–4000 |
| `LIST_CANVASES` | — | только доски, где пользователь участник |
| `JOIN_CANVAS` | `canvasId`, `token`* | только участник; `token` — устаревший способ авторизации |
| `JOIN_BY_CODE` | `code` | одноразовый код; роль берётся из кода |
| `LEAVE_CANVAS` | `canvasId` | выход из комнаты; членство и id не проверяются (дефект) |
| `RENAME_CANVAS` | `canvasId`, `name` | только владелец |
| `RESIZE_CANVAS` | `canvasId`, `width` 400–4000, `height` 300–4000 | владелец и редактор; наблюдателю `ERROR`, размер не меняется |
| `DELETE_CANVAS` | `canvasId` | только владелец; удаляет доску и её коды |
| `GEN_INVITE` | `canvasId`, `role` (`editor`/`viewer`), `hours` 1–24, `maxUses` 1–50 | владелец — editor/viewer; редактор — только viewer |
| `REVOKE_INVITE` | `canvasId`, `code` | владелец или редактор |
| `ADD_ELEMENT` | `canvasId`, `elementId`, `elementType`, `data` | editor/owner; id ≤ 128, type ≤ 32, data ≤ 6 МБ |
| `UPDATE_ELEMENT` | `canvasId`, `elementId`, `data` | editor/owner; data ≤ 6 МБ |
| `DELETE_ELEMENT` | `canvasId`, `elementId` | editor/owner |
| `CURSOR_MOVE` | `x`, `y` | любой участник активной доски; `canvasId` не используется |

## Сервер → клиент

| Тип | Поля |
|---|---|
| `AUTH_OK` | `token`, `userId`, `username`, `color`, `avatar` |
| `REGISTER_OK` / `LOGIN_OK` | `username` (+ `token`, `userId` для LOGIN) |
| `LOGOUT_OK` | — |
| `PROFILE_UPDATED` | `username`, `color`, `avatar` |
| `CANVAS_CREATED` | `canvas` (см. ниже) |
| `CANVAS_LIST` | `canvases`: массив `canvas` |
| `JOINED_CANVAS` | `canvasId`, `canvasName`, `canvasWidth`, `canvasHeight`, `ownerId`, `role`, `elements` |
| `LEFT_CANVAS` | — |
| `CANVAS_RENAMED` | `canvasId`, `name` |
| `CANVAS_RESIZED` | `canvasId`, `canvasWidth`, `canvasHeight` — всем в комнате, включая инициатора |
| `CANVAS_DELETED` | `canvasId` |
| `INVITE_CODE` | `code`, `role`, `maxUses`, `expiresAt` (epoch ms) |
| `INVITE_REVOKED` | `code`, `canvasId` |
| `ELEMENT_ADDED` | `canvasId`, `elementId`, `elementType`, `data`, `addedBy`, `username` |
| `ELEMENT_UPDATED` | `canvasId`, `elementId`, `data`, `updatedBy` |
| `ELEMENT_DELETED` | `canvasId`, `elementId`, `deletedBy` |
| `CURSOR_MOVED` | `userId`, `username`, `color`, `x`, `y` |
| `USER_JOINED` / `USER_LEFT` / `USER_UPDATED` | `userId`, `username`, `color` |
| `ERROR` | `message` |

Объект `canvas`:

```json
{"id":"...","name":"...","ownerId":"...","role":"owner|editor|viewer",
 "createdAt":0,"updatedAt":0,"canvasWidth":1920,"canvasHeight":1080,
 "width":1920,"height":1080,"memberCount":1}
```

## Формат `data` элемента

| `elementType` | Поля |
|---|---|
| `line` | `layoutX`, `layoutY`, `strokeWidth`, `color` (`#RRGGBB`), `opacity`, `points` (плоский массив x,y) |
| `text` | `x`, `y`, `width`, `height`, `text` — **цвет и размер шрифта сейчас не передаются** (см. AUDIT) |
| `figure` | `x`, `y`, `width`, `height`, `figureType` (`rect`/`circle`/`triangle`/`star`/`arrow`), `color` |
| `note` | `x`, `y`, `width`, `height`, `text`, `title`, `color` — размер шрифта не передаётся |
| `image` | `x`, `y`, `width`, `height`, `imageData` (data URL, `http(s)` или `file` URL) |

## Ограничения и ошибки

- Кадр длиннее 8 000 000 символов закрывает соединение с кодом 1009 (проверка
  идёт после приёма кадра; в байтах UTF-8 это может быть больше).
- После подключения на авторизацию даётся 20 секунд (`AUTH_TIMEOUT_SECONDS`).
  Таймаут сейчас проверяется только при входящем сообщении, поэтому молчащее
  соединение не закрывается — известный дефект (AUDIT C-04).
- `MAX_MESSAGES_PER_10S` (600) — фиксированное окно 10 секунд на соединение;
  `MAX_CONNECTIONS_PER_IP` (10) — счётчик соединений. Превышение даёт `ERROR`
  или закрытие 1008.
- Неизвестный `type` → `ERROR: Unknown message type: ...`.
- Ошибки прав и валидации приходят как `ERROR` с текстом; клиент показывает их
  тостом или в диалоге.
- Токен живёт 30 дней (TTL-индекс Mongo). Ключ устройства не истекает.

## Совместимость

Старые `LOGIN`/`REGISTER`/`LOGOUT` оставлены для совместимости, но по умолчанию
выключены. Клиент их не использует (мёртвый код UI — см. AUDIT).
