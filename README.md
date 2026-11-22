# Campus Tickets - Campus Event Booking & Ticketing (IT 109, Project Idea 4)
Stack: Nuxt 3 + Tailwind (frontend) · Javalin 6 / Java 17 (backend) · MySQL · Caffeine cache · Cloudinary · Google OAuth · PayMongo sandbox · Gmail SMTP · PSGC API · ZXing QR.

## Requirements
Java 17+, Maven 3.9+, Node 18+, MySQL 8 (XAMPP is fine - just start MySQL).

## Run
1. Backend: `cd backend`, copy `.env.example` to `.env`, fill it in, then `mvn compile exec:java` (tables are created automatically; API on :8080).
2. Frontend: `cd frontend`, copy `.env.example` to `.env`, then `npm install` and `npm run dev` (open http://localhost:3000).
3. Sign in with Google using an email listed in ADMIN_EMAILS - you become admin; promote organizers in Admin > Users & roles.

## Keys (all free / test mode)
- Google: console.cloud.google.com > Credentials > OAuth client ID (Web). Authorized JavaScript origin: http://localhost:3000. Put the ID in both .env files.
- PayMongo: dashboard > Developers > API keys > test secret key (sk_test_...). Webhook (optional, needs a public URL such as ngrok): POST /api/webhooks/paymongo, event checkout_session.payment.paid. Without it, the order page confirms payment automatically after you return from checkout.
- Gmail: Google Account > Security > 2-Step Verification > App passwords.
- Cloudinary: free account; cloud name, API key and secret.

## Demo flow
Organizer creates event + tiers > Admin approves > Attendee buys (CAPTCHA, GCash test) > webhook/sync marks Paid, QR emailed > Organizer scans at /organizer/scan (second scan is rejected).
Extra features: ticket gifting, live countdown, add-to-calendar (.ics), door-admission stats, free tiers.
