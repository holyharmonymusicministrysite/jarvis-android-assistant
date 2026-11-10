# SAM Operator API

Cloudflare Worker backend for the SAM website. It adds a server-side AI endpoint, persistent memory through D1, and an official WhatsApp Cloud API webhook.

## Free-tier architecture

- Cloudflare Workers Free: API/webhook hosting (free quota currently 100,000 requests/day).
- Workers AI: default model `@cf/zai-org/glm-4.7-flash`; free allocation currently 10,000 neurons/day. This is a quota, not unlimited inference.
- Cloudflare D1: persistent conversation and memory storage on the free plan.
- Meta WhatsApp Cloud API: official inbound/outbound messaging. Meta's pricing and template rules apply; do not assume all outbound messages are free.
- GitHub Pages remains the static website host.

Current quota details: https://developers.cloudflare.com/workers-ai/platform/pricing/ and https://developers.cloudflare.com/workers/platform/limits/

## Create Cloudflare resources (one-time)

1. Create a free Cloudflare account: https://dash.cloudflare.com/sign-up
2. In **Workers & Pages**, create a D1 database named `sam-memory`.
3. Run the SQL in `schema.sql` in the D1 console.
4. Copy the D1 database ID into `wrangler.toml` in place of `REPLACE_WITH_CLOUDFLARE_D1_DATABASE_ID`.
5. Enable Workers AI for the account.
6. Create a Cloudflare API token with permission to edit Workers and D1, and copy the account ID.
7. In the GitHub repository Settings → Secrets and variables → Actions, add:
   - `CLOUDFLARE_API_TOKEN`
   - `CLOUDFLARE_ACCOUNT_ID`
8. Add the GitHub secrets above. Then open Actions → **Deploy SAM operator API** → Run workflow. The workflow applies the D1 migration and publishes the Worker.

After first deployment, set these Worker secrets in Cloudflare → Worker → Settings → Variables and Secrets:
- `SAM_OWNER_KEY`: long random private key used by your website to authenticate.
- `WHATSAPP_VERIFY_TOKEN`: a separate random string for Meta webhook verification.
- `META_APP_SECRET`: Meta app secret (secret).
- `WHATSAPP_TOKEN`: Meta WhatsApp Cloud API access token (secret).
- `WHATSAPP_PHONE_NUMBER_ID`: the WhatsApp Business phone-number ID.
- `OWNER_WA_ID`: your WhatsApp number in international digits only, e.g. 2547XXXXXXXX (do not include +).
- Optional `SAM_ORIGIN`: the exact GitHub Pages origin.

Never put any of these secrets in website JavaScript, GitHub source files, chat messages, or screenshots. Store them only as Cloudflare Worker secrets.

## Connect Meta WhatsApp

1. In https://developers.facebook.com/ create a Meta app and add the WhatsApp product.
2. Start with Meta's test business number and add your personal WhatsApp number as an allowed test recipient.
3. Copy the access token, phone-number ID, and app secret into Worker secrets.
4. Set the Meta webhook callback URL to `https://sam-operator-api.<your-subdomain>.workers.dev/webhook`.
5. Set the verify token to the exact `WHATSAPP_VERIFY_TOKEN` value.
6. Subscribe the webhook to the `messages` field.
7. Send a test message to the Meta test number. SAM will reply only to the configured `OWNER_WA_ID`.

Official setup: https://whatsappbusiness.com/developers/developer-hub/

## API

- `GET /health` — service readiness (does not expose secrets).
- `POST /api/chat` — JSON `{"message":"..."}`, Bearer `SAM_OWNER_KEY`.
- `POST /api/whatsapp/send` — JSON `{"message":"..."}`, Bearer key. WhatsApp's 24-hour customer-service window and approved-template rules still apply.
- `GET /api/memory` and `POST /api/memory` — persistent memory.
- `GET /webhook` — Meta webhook verification.
- `POST /webhook` — signed Meta webhook events.

## Calls

WhatsApp Business Calling is a separate capability. Meta requires an eligible WhatsApp Business sender and user call permission; a production voice agent also needs a real-time media endpoint (for example WebRTC/SIP), not merely a text webhook. This API deliberately does not pretend to place or answer calls. Calling will be added only after the sender is eligible and a supported media service is configured.

## Security and action approvals

The API authenticates website calls with a private owner key, verifies Meta webhook signatures, and ignores messages from any WhatsApp sender except the configured owner number. The model is instructed to propose external actions for approval rather than claiming to perform them. This backend does not currently grant arbitrary shell, filesystem, banking, or phone-control access.

## Troubleshooting

- `Unauthorized`: check the website's private API key matches `SAM_OWNER_KEY`.
- `Invalid signature`: check `META_APP_SECRET` and webhook delivery.
- WhatsApp send failure: check token, phone-number ID, recipient opt-in, account quality, and 24-hour/template rules.
- AI failure: check Workers AI is enabled and the daily free neuron quota has not been exhausted.