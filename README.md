# Android Contact Sync App

This Android Studio project reads the device contact list and syncs it to the Strapi backend in the repo root.

## What it does

- Requests `READ_CONTACTS`
- Reads names, phone numbers, and first available email address
- Finds or creates the Strapi `User` record using the device's Android ID as `device_id`
- Creates or updates `Contact` records in Strapi by matching `user + phone`

## Open In Android Studio

1. Open Android Studio
2. Choose `Open`
3. Select the [android-app](/c:/Personal/Codex/Contact/android-app) folder
4. Let Android Studio sync the Gradle project

This project targets Android SDK 34 and expects Android Studio's embedded JDK 17.

## Configure The API

- Emulator base URL: `http://10.0.2.2:1337`
- Real device base URL: replace with your computer's LAN IP, for example `http://192.168.1.10:1337`
- If your Strapi API is not public, create a Strapi API token and paste it into the app

## Strapi Permissions

If you are not using an API token, enable the following `Public` permissions in Strapi:

- `app-user`: `find`, `create`
- `contact`: `find`, `create`, `update`

## Notes

- Contacts are de-duplicated on-device by `name + phone`
- Server sync matches by `user + phone`, so repeat syncs update existing records instead of always inserting new ones
