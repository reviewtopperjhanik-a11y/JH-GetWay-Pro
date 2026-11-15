# JH GetWay — GitHub Auto-Release & Permanent Signing Guide
# (গিটহাব অটো-রিলিজ ও স্থায়ী সাইনিং গাইড)

এই ডকুমেন্টটি **JH GetWay** প্রজেক্টের GitHub Actions অটো-বিল্ড, স্থায়ী APK সাইনিং এবং ঝামেলামুক্ত ইন-অ্যাপ আপডেটের জন্য স্থায়ী রেফারেন্স নোট। ভবিষ্যতে যেকোনো আপডেট, নতুন ডিভাইস বা রিলিজ সংক্রান্ত কাজের সময় এই ডকুমেন্ট অনুসরণ করুন।

---

## ১. অ্যাপের স্থায়ী পরিচয় (কখনো পরিবর্তন করবেন না)

প্রতিবার পুরোনো অ্যাপ **Uninstall না করে** সরাসরি তার ওপরে নতুন আপডেট ইনস্টল হতে হলে এই ৩টি বিষয় আজীবন এক থাকতে হবে:

| Property | Value | Source |
|---|---|---|
| **Application ID** | `com.aistudio.jhgetway.iffndf` | `app/build.gradle.kts` (`defaultConfig.applicationId`) |
| **Release Key Alias** | `upload` | `app/build.gradle.kts` (`signingConfigs.release`) |
| **Signing Certificate** | আপনার তৈরি করা স্থায়ী `.jks` ফাইল | GitHub Actions Secrets (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) |

> **গুরুত্বপূর্ণ:** `versionCode` প্রতি রিলিজে আগের রিলিজের চেয়ে বড় হতে হবে (ওয়ার্কফ্লো এটি স্বয়ংক্রিয়ভাবে করে)। কোনো GitHub Secret না থাকলে ওয়ার্কফ্লো কখনোই অস্থায়ী/নতুন Key বানাবে না—বরং Error দিয়ে থেমে যাবে (`fail-closed`), যাতে ভুল Key-তে সাইন হওয়া APK কখনো রিলিজ না হয়।

---

## ২. প্রথমবার (জীবনে কেবল একবার) স্থায়ী Keystore তৈরি ও GitHub Secrets সেটআপ

### ধাপ ২.১: একটি স্থায়ী Keystore ফাইল (`my-upload-key.jks`) তৈরি করুন

আপনার নিজস্ব কম্পিউটার বা টার্মিনালে (যেখানে Java `keytool` আছে) নিচের কমান্ডটি **কেবল একবার** চালান:

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12 \
  -dname "CN=JH Soft Corporation, OU=Mobile, O=JH Soft Corporation, C=BD"
```

- কমান্ড চালানোর সময় একটি শক্তিশালী **Keystore Password** এবং **Key Password** দিন (সহজ ব্যবস্থাপনার জন্য দুটো পাসওয়ার্ড একই রাখতে পারেন, অথবা আলাদা রাখলে দুটোই নিরাপদ জায়গায় লিখে রাখুন)।
- **সতর্কতা:**
  - `my-upload-key.jks` ফাইলটি **কখনোই** GitHub repository-তে commit/push করবেন না (`.gitignore`-এ `.jks` ইতিমধ্যে ব্লক করা আছে)।
  - ফাইলটি ও পাসওয়ার্ড কমপক্ষে ২টি নিরাপদ অফলাইন জায়গায় (যেমন Google Drive/Password Manager/USB) ব্যাকআপ রাখুন। এটি হারালে ভবিষ্যতে একই অ্যাপের ওপরে আর আপডেট দেওয়া যাবে না।

### ধাপ ২.২: সার্টিফিকেটের SHA-256 Fingerprint বের করুন (ঐচ্ছিক কিন্তু সুপারিশকৃত)

```bash
keytool -list -v -keystore my-upload-key.jks -alias upload
```

আউটপুটে `SHA256:` লাইনে থাকা হেক্স ভ্যালুটি কপি করে রাখুন (যেমন `AA:BB:CC:...`)।

### ধাপ ২.৩: `.jks` ফাইলকে Base64 টেক্সটে রূপান্তর করুন

- **Linux / Git Bash:**
  ```bash
  base64 -w 0 my-upload-key.jks
  ```
- **macOS:**
  ```bash
  base64 -i my-upload-key.jks | tr -d '\n'
  ```
- **Windows PowerShell:**
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("my-upload-key.jks"))
  ```

### ধাপ ২.৪: GitHub Repository Secrets ও Variables যোগ করুন

GitHub-এ আপনার repository খুলে যান:  
**Settings -> Secrets and variables -> Actions**

#### **Repository secrets** ট্যাবে গিয়ে নিচের ৩টি Secret যোগ করুন (বাধ্যতামূলক):
1. `KEYSTORE_BASE64` -> ধাপ ২.৩-এ পাওয়া পুরো Base64 স্ট্রিং
2. `STORE_PASSWORD` -> আপনার Keystore পাসওয়ার্ড
3. `KEY_PASSWORD` -> আপনার Key (`upload` alias) পাসওয়ার্ড

#### **Variables** ট্যাবে গিয়ে নিচের ১টি Variable যোগ করুন (ঐচ্ছিক কিন্তু নিরাপদ):
1. `EXPECTED_SIGNING_SHA256` -> ধাপ ২.২-এ পাওয়া `SHA256` fingerprint (এটি দিলে CI প্রতিবার নিশ্চিত করবে যে ঠিক আপনার আসল Key দিয়েই APK সাইন হয়েছে)

---

## ৩. প্রথম স্থায়ী APK ইনস্টল (কেবল একবারের মাইগ্রেশন)

যদি আপনার ফোনে আগে থেকে অন্য কোনো Key (যেমন debug key বা আগের কোনো অস্থায়ী CI key) দিয়ে সাইন করা `JH GetWay` ইনস্টল থাকে:
1. আগে অ্যাপের ভেতর **Settings -> Backup & Restore -> Export Encrypted Backup** থেকে আপনার সব প্রোভাইডার/সেটিংস/চ্যাট ব্যাকআপ নিয়ে নিরাপদে সংরক্ষণ করুন।
2. পুরোনো অ্যাপটি ফোনে **কেবল শেষবারের মতো একবার** Uninstall করুন।
3. GitHub Releases থেকে নতুন স্থায়ী Key দিয়ে সাইন করা প্রথম APK (`JH-GetWay-v*-release.apk`) ডাউনলোড করে ইনস্টল করুন এবং প্রয়োজন হলে ব্যাকআপ Restore করুন।

> **এরপর থেকে আর কখনোই Uninstall করতে হবে না!** প্রতিবার নতুন আপডেট সরাসরি আগের অ্যাপের ওপরেই ইনস্টল হবে এবং আপনার সব ডেটা, Room Database, API Key ও সেটিংস অক্ষত থাকবে।

---

## ৪. প্রতিদিনের রিলিজ ও আপডেট কীভাবে কাজ করে

ওয়ার্কফ্লো ফাইল: `.github/workflows/android-release.yml`

### ক) স্বয়ংক্রিয় রিলিজ (`main` বা `master` ব্রাঞ্চে Push করলেই)
1. আপনি যখনই কোড `main` (বা `master`) ব্রাঞ্চে push করবেন, `Android CI & Auto Release` ওয়ার্কফ্লো স্বয়ংক্রিয়ভাবে চালু হবে।
2. **Job 1 (`test`):** প্রথমে পুরো Unit ও Robolectric টেস্ট (`:app:testDebugUnitTest`) এবং Debug build (`:app:assembleDebug`) চালাবে। টেস্ট ফেল করলে রিলিজ তৈরি হবে না।
3. **Job 2 (`release`):** টেস্ট পাস করলে:
   - GitHub-এর সর্বশেষ রিলিজ দেখে স্বয়ংক্রিয়ভাবে পরের সেমান্টিক ভার্সন (যেমন `v1.0.1` -> `v1.0.2`) এবং আগের চেয়ে বড় `versionCode` হিসাব করে নেবে।
   - আপনার GitHub Secrets-এর স্থায়ী Keystore (`upload` alias) ব্যবহার করে `:app:assembleRelease` তৈরি ও সাইন করবে।
   - `aapt2` ও `apksigner` দিয়ে যাচাই করবে যে APK-র `applicationId`, `versionCode`, `versionName` এবং সার্টিফিকেট SHA-256 একদম ঠিক আছে।
   - নিচের ৩টি ফাইলসহ নতুন **GitHub Release** পাবলিশ করবে:
     - `JH-GetWay-vX.Y.Z-release.apk`
     - `update-manifest.json`
     - `SHA256SUMS.txt`

### খ) ম্যানুয়ালি নির্দিষ্ট ভার্সন রিলিজ করতে চাইলে
- **GitHub UI থেকে:** `Actions -> Android CI & Auto Release -> Run workflow` ক্লিক করে চাইলে `version_name` (যেমন `1.2.0`) বা `version_code` লিখে রান করতে পারেন (ফাঁকা রাখলে অটো ইনক্রিমেন্ট হবে)।
- **Tag push করে:**
  ```bash
  git tag v1.2.0
  git push origin v1.2.0
  ```

### গ) Pull Request-এ কী হবে?
- Pull Request (`pull_request` on `main`/`master`) করলে শুধু `test` জব চলবে—কোনো Release পাবলিশ হবে না।

---

## ৫. ইন-অ্যাপ অটো আপডেট (`AppUpdateManager.kt`)

- GitHub Actions দিয়ে তৈরি হওয়া প্রতিটি Release APK-তে `BuildConfig.GITHUB_REPO` (যেমন `owner/repo`) স্বয়ংক্রিয়ভাবে যুক্ত থাকে।
- অ্যাপ চালু হলে বা **Settings -> App Updates (GitHub Releases)** থেকে চেক করলে অ্যাপটি `releases/latest` থেকে `update-manifest.json` পড়ে:
  1. `packageId` (`com.aistudio.jhgetway.iffndf`) ও `versionCode` মেলায়,
  2. ডাউনলোড করা APK-র `SHA-256` হ্যাশ যাচাই করে,
  3. ইনস্টল করা অ্যাপের সাথে ডাউনলোড করা APK-র Signing Certificate মিলিয়ে নিশ্চিত হয় যে এটি ইন-প্লেস আপডেট নিতে পারবে,
  4. তারপর Android Package Installer ওপেন করে।

---

## ৬. সাধারণ সমস্যা ও সমাধান (Troubleshooting)

| সমস্যা / Error | কারণ | সমাধান |
|---|---|---|
| `Missing required permanent signing secret(s): KEYSTORE_BASE64 ...` | GitHub Secrets-এ ৩টি Secret এখনো যোগ করা হয়নি। | **Settings -> Secrets and variables -> Actions**-এ `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD` যোগ করে ওয়ার্কফ্লো পুনরায় চালান। |
| `Could not open keystore or find alias 'upload'` | পাসওয়ার্ড ভুল অথবা Keystore তৈরি করার সময় alias `upload` দেওয়া হয়নি। | সঠিক পাসওয়ার্ড দিন অথবা `-alias upload` দিয়ে Keystore যাচাই করুন। |
| `Keystore SHA-256 does not match EXPECTED_SIGNING_SHA256` | ভ্যারিয়েবলে পিন করা fingerprint-এর সাথে Keystore মিলছে না। | `keytool -list -v` দিয়ে সঠিক SHA-256 মিলিয়ে নিন। |
| ফোনে আপডেটের সময় `App not installed as package conflicts with an existing package` | ফোনের বর্তমান অ্যাপটি ভিন্ন Key (পুরোনো অস্থায়ী/debug key) দিয়ে সাইন করা ছিল। | ব্যাকআপ এক্সপোর্ট করে পুরোনো অ্যাপটি **একবার** Uninstall করুন, তারপর নতুন স্থায়ী Release APK ইনস্টল করুন। এরপর আর কখনো এটি হবে না। |
