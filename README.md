# Android Contact Sync App

This Android Studio project reads contacts, syncs them to Strapi, and uploads gallery images to S3 using presigned URLs.
It now supports separate white-label Android builds per tenant using product flavors.

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

## White-Label Flavors

Current configured flavors are driven by [tenants.json](/c:/Personal/Codex/Contact-Mobile/android-app/tenants.json).

Each flavor can have its own:

- `applicationId`
- app name
- launcher icon
- colors and branding
- tenant API key
- backend base URL

Flavor values are configured from [tenants.json](/c:/Personal/Codex/Contact-Mobile/android-app/tenants.json) and loaded by [app/build.gradle.kts](/c:/Personal/Codex/Contact-Mobile/android-app/app/build.gradle.kts).

## Add A New Tenant

The lowest-manual-work flow is:

1. Create the backend tenant and copy its API key
2. Copy the tenant logo from the backend portal if you have one
3. Run the scaffold script:

```powershell
cd C:\Personal\Codex\Contact-Mobile\android-app
.\scripts\add-tenant.ps1 `
  -Slug memberrewardplus `
  -AppName "Member Reward Plus" `
  -ApplicationId "com.memberreward.contact.memberrewardplus" `
  -PrimaryColor "#1D4ED8" `
  -InAppLogoPath "C:\Branding\memberrewardplus-logo.png" `
  -LogoStyle card
```

Optional:

- `-BrandName`
- `-ApiKeyEnv`
- `-AppSubtitle`
- `-OnPrimaryColor`
- `-SurfaceColor`
- `-AccentColor`
- `-InAppLogoPath`
- `-LauncherIconPath`

If you omit `-SurfaceColor` and `-AccentColor`, the script auto-derives them from `-PrimaryColor`.
If you omit `-LauncherIconPath` but provide `-InAppLogoPath`, the script reuses the same copied logo file for the launcher icon.

The script will:

- add/update the tenant in `tenants.json`
- create `app/src/<slug>/`
- generate `AndroidManifest.xml`
- generate flavor `strings.xml`
- generate flavor `colors.xml`
- generate the in-app displayed logo drawable
- generate the launcher icon drawable

If you pass `-InAppLogoPath` or `-LauncherIconPath`, those files are copied in instead of the default generated assets.

### What To Copy From The Backend Portal

The backend portal currently gives you:

- the tenant `primary_color`
- the uploaded tenant logo

Recommended usage:

- `PrimaryColor`: copy exactly from the backend portal
- `SurfaceColor`: let the script derive it unless you want to override it
- `AccentColor`: let the script derive it unless you want to override it

For logos:

- `InAppLogoPath`: use the full tenant logo or brand mark you want shown inside the app screens
- `LauncherIconPath`: use a simpler square logo if you have one

If you only have one logo from the backend portal:

1. Save it locally as a PNG or WEBP
2. Pass it as `-InAppLogoPath`
3. Skip `-LauncherIconPath` and the script will reuse that same file for the launcher icon

Best practice:

- in-app logo: full brand logo
- launcher icon: simplified symbol or badge if available

Supported copied asset formats:

- `.png`
- `.webp`
- `.jpg`
- `.jpeg`
- `.xml`

## Configure The API

Set these values before building:

- `APP_BASE_URL`
- one API key per tenant listed in `tenants.json`, for example:
  - `APP_API_KEY_MEMBERREWARD`
  - `APP_API_KEY_DUMMYALPHA`
  - `APP_API_KEY_DUMMYBETA`

You can also provide `APP_API_KEY` as a fallback default.

Important: on Android Emulator, `localhost` points to the emulator itself. If your backend runs on your computer, use `http://10.0.2.2:1337` instead.

## Authentication

Each flavor should use the tenant-specific `app_api_key` created in the backend `Tenant` record.

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
- Optional key prefix strategy (now `users/<tenant-slug>/<userId>/images/...`)

## Notes

- Contacts are de-duplicated on-device by `name + phone`
- Server sync matches by `tenant + user + phone`, so repeat syncs update existing records instead of always inserting new ones
- Production admin portal: `https://cmsportal.yengsang.com/admin`
- Put tenant API keys in your global Gradle properties file when possible:
  `C:\Users\<your-user>\.gradle\gradle.properties`
