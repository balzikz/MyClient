# Восстановление MATH Client · 13 сентября 2026

## Что действительно изучено

| Источник | Проверенный снимок | Ограничение |
| --- | --- | --- |
| `balzikz/MyClient`, `main` | `a2efe6d9e57270f19837ecab513ec4d8a443f554` | Не равен последней экспериментальной ветке |
| `flarial-parity-bootstrap` | `6d8bc4ef98f6f8985a8c9862e96f66443de0f810` | Проверены Gradle, manifest, Activity/Application/Service, manager, shim/client |
| `bedrock_clients_report_v1.md` | 28 июня, исходный файл | Часть выводов относится к старому main, не ко всем веткам |
| «Форензическое сравнение Flarial и MATH MyClient…» | исходный DOCX | Сам отчёт сообщает, что автор тогда не получил repo/APK; это гипотезы, не прямая бинарная экспертиза |
| `math-stage397-diagnostics-1782407770889.txt` | raw log | `onCreate` вернулся, затем переход к `onStart`; нет подтверждения кадра |
| `math-stage398-diagnostics-1782410679125.txt` | raw log | повторы попыток и отказ около `onStart`; нужна полноценная привязка crash stack |
| `math-stage51-diagnostics-1782458343168.txt` | raw log | явный fail-fast по отсутствию legacy entrypoints после JVM load |
| `Flarial.zip` | APK в ZIP-формате, ELF `readelf` | Код не исполнялся; изучены native inventory, DT_NEEDED и экспортируемые символы |

SHA-256 исходного Flarial APK:
`e9867dbda3ce55b0cae45956b18390d098578229668139c45505f40f6bc38048`.

- `libshin.so`: `60bfabce78a8b8d3d5be3ba6113c115c2212f428978ed64bfbeae957cd941ffa`.
- `libflarialclient.so`: `175850033542aeb8ebd5f6e4e1557b9254e29d59ec213f84609e319f9221a166`.

В исследованном `libshin.so` присутствуют экспорты `ANativeActivity_onCreate`,
`ANativeActivity_finish`, `android_main`, `JNI_OnLoad`; фильтр dynsym не обнаружил
`GameActivity_onCreate`. Это не доказывает, какой путь реально выполняется при запуске.
Наличие экспортов нельзя подменять утверждением об исполнении функций.

## Подтверждено по позднему коду

1. В `LauncherApplication.onCreate` основной процесс вызывает `HostJournal.reset`.
   Следовательно, повторный запуск лаунчера способен уничтожить нужную диагностику.
2. `GamePackageManager` использует фиксированные списки библиотек и preload-order.
   APK-контейнеры фильтруются по имени (`base`, `arm`, `x86`, `install_pack`).
3. Повторное использование отдельных `.so` допускается при равной длине файла.
   Это не доказательство тождества содержимого.
4. В `parity_client.cpp` выполняется регистрация JNI и сохранение Activity reference.
   Эквивалентность этой заглушки раннему bootstrap оригинального клиента не доказана.
5. `parity_shin.cpp` игнорирует результат вспомогательной регистрации методов
   при возврате из `JNI_OnLoad`. Успешный возврат не гарантирует все регистрации.
6. Поздний manifest уже содержит Application, Activity и процесс `:client`.
   Переносить на него все P0-ошибки из отчёта о `main` было бы неверно.
7. В Stage 5.1 зарегистрирована версия 1.26.30.5 / versionCode 972603005.
   В Stage 3.9.x runtime-пути относятся к 1.26.31.1. Это разные опыты.

## Не установлено

- Единственная первопричина старого SIGABRT; влияние конкретной ревизии GameActivity.
- Реальные ранние side effects оригинального `libflarialclient.so`.
- Точная Java/JNI таблица и SHA текущего `libminecraftpe.so` пользователя.
- Первый игровой кадр. В прочитанных источниках подтверждения нет.

`SurfaceView=0x0` непосредственно в `onCreate`, до layout/surface callbacks,
само по себе не доказывает дефект. Новая сборка записывает реальные
`surfaceCreated`, `surfaceChanged`, размеры и результат собственного EGL swap.

## Решение первого этапа

Новый независимый проект `rebuild/`, отдельный package, без переноса старого
bootstrap. Сначала инвентаризация и наблюдаемость. Единственная исполняемая
native-библиотека — собственная `libmathprobe.so`.

Различаем: обнаружение пакета → инвентаризация файлов → регистрация **своего** JNI
→ создание **своей** surface → отправка **своего** кадра. Ни одно из этих событий
не записывается как `MINECRAFT_STARTED` или `GAME_FIRST_FRAME`.

Для будущего game-adapter не устанавливаем произвольный fail-fast по отсутствию
`android_main`: корректная точка входа зависит от проверенного Java/native контракта.
Не вызываем `JNI_OnLoad` вручную, не отключаем лицензирование, не меняем установленную игру.

## Проверенные первичные справочники

- [Android JNI tips](https://developer.android.com/ndk/guides/jni-tips): VM load owner, FindClass, RegisterNatives.
- [GameActivity integration](https://developer.android.com/games/agdk/game-activity/get-started): контракт Activity/native glue требует согласования.
- [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo): история завершений API 30; native tombstone API 31+, может отсутствовать.
- [FileProvider sharing](https://developer.android.com/training/secure-file-sharing/setup-sharing): приватный provider и временный URI grant.
- [AGP 9.2](https://developer.android.com/build/releases/agp-9-2-0-release-notes): Gradle 9.4.1, JDK 17; исправление 9.2.1.
- [16 KiB pages](https://developer.android.com/guide/practices/page-sizes): собственная native-сборка включает flexible page sizes.

Старые APK и диагностические источники остаются исходными материалами; в этот
проект включены только выводы и новый собственный код.
