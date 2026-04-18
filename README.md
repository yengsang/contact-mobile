# Android Contact Sync App

This Android Studio project reads contacts, syncs them to Strapi, and uploads gallery images to S3 using presigned URLs.

## What it does

- Requests `READ_CONTACTS`
- Requests gallery image permission (`READ_MEDIA_IMAGES` on Android 13+, `READ_EXTERNAL_STORAGE` on older devices)
- Reads names, phone numbers, and first available email address
- Finds or creates the Strapi `User` using the email and phone entered in the app
- Creates or updates `Contact` records in Strapi by matching `user + phone`
- Uploads all gallery images to S3 after getting per-file presigned upload URLs from backend

## Open In Android Studio

1. Open Android Studio
2. Choose `Open`
3. Select the [android-app](</c:/Personal/Codex/Contact-Mobile/android-app>) folder
4. Let Android Studio sync the Gradle project

This project targets Android SDK 34 and expects Android Studio's embedded JDK 17.

## Configure The API

- The app is currently hardcoded to `http://localhost:1337` for local backend testing.
- API token is currently hardcoded in code for testing.

Important: on Android Emulator, `localhost` points to the emulator itself. If your backend runs on your computer, use `http://10.0.2.2:1337` instead.

## Authentication

Create a Strapi API token in the admin portal and use that in the app.

## S3 Upload Integration

The app expects a backend endpoint:

- `POST /api/s3/presign`

Request payload per image:

```json
{
  "fileName": "image.jpg",
  "contentType": "image/jpeg"
}
```

Expected response:

```json
{
  "uploadUrl": "https://<bucket>.s3.<region>.amazonaws.com/....",
  "headers": {
    "Content-Type": "image/jpeg"
  }
}
```

The app then uploads directly to S3 with HTTP `PUT`.

Info needed from you to finish S3 end-to-end:

- AWS region
- Bucket name
- Backend endpoint implementation for `/api/s3/presign`
- CORS policy on bucket allowing browser/mobile `PUT` with required headers
- Optional key prefix strategy (for example `users/<userId>/images/...`)

## Notes

- Contacts are de-duplicated on-device by `name + phone`
- Server sync matches by `user + phone`, so repeat syncs update existing records instead of always inserting new ones
- Production admin portal: `https://cmsportal.yengsang.com/admin`
