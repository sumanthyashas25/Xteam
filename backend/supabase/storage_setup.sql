-- X.MAX Zero-Cost Supabase Storage Bucket Provisioning (Free Tier: 1 GB storage / 5 GB monthly egress)
-- Run once in the Supabase SQL Editor. All buckets are PRIVATE (public = false).
-- Access is granted exclusively via short-lived signed URLs from the Cloudflare Worker.

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES
  ('avatars', 'avatars', false, 2097152, ARRAY['image/jpeg', 'image/png', 'image/webp']),
  ('group-photos', 'group-photos', false, 2097152, ARRAY['image/jpeg', 'image/png', 'image/webp']),
  ('chat-media', 'chat-media', false, 5242880, ARRAY['image/jpeg', 'image/png', 'image/webp']),
  ('chat-voice', 'chat-voice', false, 2097152, ARRAY['audio/mp4', 'audio/aac', 'audio/m4a', 'audio/mpeg']),
  ('chat-videos', 'chat-videos', false, 15728640, ARRAY['video/mp4', 'video/webm', 'image/jpeg']),
  ('chat-docs', 'chat-docs', false, 10485760, ARRAY['application/pdf', 'text/plain', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'application/zip'])
ON CONFLICT (id) DO NOTHING;

-- Lock down direct anonymous/public reads; service_role via Cloudflare Worker mints signed URLs.
DROP POLICY IF EXISTS "Deny public direct storage reads" ON storage.objects;
CREATE POLICY "Deny public direct storage reads"
  ON storage.objects
  FOR ALL
  TO anon, authenticated
  USING (false)
  WITH CHECK (false);
