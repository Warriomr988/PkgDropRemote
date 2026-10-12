# PkgDrop Remote 📲🎮

App Android compañera del homebrew **PkgDrop** (PS4). Es el alimentador +
control remoto que hoy es una página web en `http://<ps4>:8080`:

- **Compartir enlace** desde cualquier app/navegador → la app lo encola.
- **Descubre la PS4 sola** (DDP por UDP:987) o IP manual en ajustes.
- **Verifica el RPI** (`/api/is_exists`) antes de enviar, en vez de fallar a ciegas.
- **Dos modos**:
  - **Delegar a PkgDrop**: URLs directas → `POST /add` al homebrew; la PS4 descarga e instala.
  - **Teléfono sirve**: PKG elegido por SAF → servidor HTTP propio (`:8080`, con `Range` + tracking de cobertura) → `install` al RPI con polling de progreso real por `task_id`.
- **Cola con progreso real**: %, velocidad, ETA; pausar/reanudar/cancelar.
- Sin ads, sin analytics, UI en español.

`com.panda.pkgdropremote` · minSdk 26 · target/compile 34 · v1.0 (versionCode 1)

## Compilar

- **GitHub Actions**: push a `main` → artifact `PkgDropRemote-debug` (30 días).
- **Termux/Panda**: proyecto estándar AGP 8.13; si aapt2 falla, ver nota en `gradle.properties`.

## Probar

1. Instala el APK, abre la app, toca **🔍 Buscar PS4** (o escribe la IP manual).
2. Verifica que el punto RPI salga **● en línea** (GoldHEN activo en la PS4).
3. **Modo delegar**: pega URLs en el campo → *Encolar URLs* → el PkgDrop de la PS4 las descarga.
   O comparte un enlace desde Chrome con *Compartir → PkgDrop Remote*.
4. **Modo servir**: *Elegir PKG del teléfono* → el teléfono lo sirve y el RPI lo instala;
   mira el %/velocidad en la cola y en la notificación.
