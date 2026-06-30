# Slide web

The customer website and browser call client for **Slide**, a phone-only video
calling app for iOS, Android, and the web.

**Live:** https://slide.viraat.dev

Built with **Next.js (App Router, TypeScript)** + **Tailwind CSS**, following the
"quiet & precise" design system in [`../AGENTS.md`](../AGENTS.md):
pure-white backgrounds, thin near-black type, hairline dividers, generous
whitespace, and fast/subtle motion that respects `prefers-reduced-motion`.

## Pages

| Route      | Description                                                        |
| ---------- | ----------------------------------------------------------------- |
| `/`        | Customer-facing product site with platform badges and feature copy.|
| `/web`     | Browser client with phone OTP login, notifications, and calls.     |
| `/privacy` | Privacy Policy (phone-as-identity, hashed contacts, no data sale).|
| `/terms`   | Terms of Service.                                                  |

Also generates `/robots.txt` and `/sitemap.xml`, and a thin "S" favicon.

## Local development

```bash
cd web
npm install
npm run dev      # http://localhost:3000
```

## Production build

```bash
npm run build    # type-checks + builds; must succeed with zero errors
npm run start    # serve the production build locally
```

## Deploy (Vercel)

The project is linked to the Vercel project `viraatdas-projects/web`.
Set `NEXT_PUBLIC_VAPID_PUBLIC_KEY` to the public half of the backend's VAPID
keypair; a mismatch lets subscription appear successful but makes delivery
fail. Set `NEXT_PUBLIC_SLIDE_API_BASE_URL` when deploying against a non-default
API host.

```bash
vercel --yes            # preview deploy
vercel --prod --yes     # production deploy
```

> After changing the canonical domain, update `siteUrl` in
> `src/app/layout.tsx` and the `base` URLs in `src/app/sitemap.ts` /
> `src/app/robots.ts` so Open Graph tags, the sitemap, and robots.txt point at
> the live host.

## Project structure

```
web/
├── src/
│   ├── app/
│   │   ├── layout.tsx        # metadata, Open Graph, fonts, favicon
│   │   ├── page.tsx          # customer-facing homepage
│   │   ├── globals.css       # tokens + scroll-reveal + reduced-motion
│   │   ├── not-found.tsx     # 404
│   │   ├── robots.ts
│   │   ├── sitemap.ts
│   │   ├── web/page.tsx
│   │   ├── privacy/page.tsx
│   │   └── terms/page.tsx
│   └── components/
│       ├── Nav.tsx
│       ├── Footer.tsx
│       ├── Reveal.tsx        # IntersectionObserver scroll-reveal
│       ├── SlideWebApp.tsx   # phone auth, push/signaling, LiveKit calling
│       ├── StoreBadges.tsx   # thin Web/App Store/Google Play pills
│       ├── PhoneMockup.tsx   # CSS device frame + Slide UI
│       ├── Legal.tsx         # shared shell for /privacy and /terms
│       └── icons.tsx         # 1.5px thin-line icons
├── public/
│   ├── favicon.svg
│   └── icon.svg
├── tailwind.config.ts        # design tokens
└── ...
```
