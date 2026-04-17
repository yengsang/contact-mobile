# Android Contact Sync App

This Android Studio project reads the device contact list and syncs it to the production Strapi backend.

## What it does

- Requests `READ_CONTACTS`
- Reads names, phone numbers, and first available email address
- Finds or creates the Strapi `User` record using the device's Android ID as `device_id`
- Creates or updates `Contact` records in Strapi by matching `user + phone`

## Open In Android Studio

1. Open Android Studio
2. Choose `Open`
3. Select the [android-app](</c:/Personal/Codex/Contact-Mobile/android-app>) folder
4. Let Android Studio sync the Gradle project

This project targets Android SDK 34 and expects Android Studio's embedded JDK 17.

## Configure The API

- Default production API base URL: `https://api.yengsang.com`
- Paste your Strapi API token into the app before syncing
- Only change the base URL if you intentionally want to test against a different environment

## Authentication

Create a Strapi API token in the admin portal and use that in the app.

## Notes

- Contacts are de-duplicated on-device by `name + phone`
- Server sync matches by `user + phone`, so repeat syncs update existing records instead of always inserting new ones
- Production admin portal: `https://cmsportal.yengsang.com/admin`
