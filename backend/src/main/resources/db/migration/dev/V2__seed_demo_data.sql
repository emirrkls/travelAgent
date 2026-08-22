INSERT INTO users (
    id, username, display_name, avatar_url, bio, city_count, country_count,
    followers_count, following_count, travel_taste, created_at, updated_at
) VALUES (
    '11111111-1111-1111-1111-111111111111', 'emir_demo', 'Emir Kaya',
    'https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=400',
    'İyi kahve, sakin koylar ve uzun hafta sonları peşinde.',
    9, 1, 284, 173, ARRAY['Aegean food', 'Hidden coves', 'Specialty coffee', 'Ancient cities'],
    '2025-01-01T10:00:00Z', '2025-06-15T18:00:00Z'
);

INSERT INTO places (
    id, name, description, category, subcategories, location, city, region, country,
    address, cover_image, photos, price_level, created_at, updated_at
) VALUES
('20000000-0000-0000-0000-000000000001', 'Mimoza Sofrası', 'Mevsimlik Ege ürünlerini taş avluda sunan sakin bir mahalle lokantası.', 'RESTAURANT',
 ARRAY['Aegean','Seasonal','Courtyard'], ST_SetSRID(ST_MakePoint(27.2844, 37.1035), 4326), 'Bodrum', 'Muğla', 'Türkiye',
 'Yokuşbaşı Mah., Bodrum', 'https://images.unsplash.com/photo-1414235077428-338989a2e8c0?w=1200', ARRAY['https://images.unsplash.com/photo-1559339352-11d035aa65de?w=1200'],
 3, '2025-01-01T10:00:00Z', '2025-06-01T10:00:00Z'),
('20000000-0000-0000-0000-000000000002', 'Kaktüs Coffee Lab', 'Alaçatı arka sokaklarında küçük parti çekirdekler kavuran aydınlık bir kahve dükkânı.', 'CAFE',
 ARRAY['Specialty coffee','Roastery'], ST_SetSRID(ST_MakePoint(26.3747, 38.2822), 4326), 'Alaçatı', 'İzmir', 'Türkiye',
 'Hacımemiş Mah., Alaçatı', 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?w=1200', ARRAY['https://images.unsplash.com/photo-1495474472287-4d71bcdd2085?w=1200'],
 2, '2025-01-02T10:00:00Z', '2025-06-02T10:00:00Z'),
('20000000-0000-0000-0000-000000000003', 'Sarnıç Koyu', 'Çam tepelerinin berrak Ege suyuyla buluştuğu, patikayla ulaşılan korunaklı koy.', 'BEACH',
 ARRAY['Hidden cove','Swimming'], ST_SetSRID(ST_MakePoint(27.3991, 36.9988), 4326), 'Bodrum', 'Muğla', 'Türkiye',
 'Mazı yolu, Bodrum', 'https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200', ARRAY['https://images.unsplash.com/photo-1473116763249-2faaef81ccda?w=1200'],
 2, '2025-01-03T10:00:00Z', '2025-06-03T10:00:00Z'),
('20000000-0000-0000-0000-000000000004', 'Taş Otel Kaş', 'Begonvilli balkonları ve eski liman manzarasıyla küçük bir taş otel.', 'HOTEL',
 ARRAY['Boutique','Sea view'], ST_SetSRID(ST_MakePoint(29.6377, 36.2018), 4326), 'Kaş', 'Antalya', 'Türkiye',
 'Andifli Mah., Kaş', 'https://images.unsplash.com/photo-1566073771259-6a8506099945?w=1200', ARRAY['https://images.unsplash.com/photo-1582719478250-c89cae4dc85b?w=1200'],
 3, '2025-01-04T10:00:00Z', '2025-06-04T10:00:00Z'),
('20000000-0000-0000-0000-000000000005', 'Avlu 1923', 'Anadolu üreticilerinden gelen malzemelere odaklanan modern semt mutfağı.', 'RESTAURANT',
 ARRAY['Anatolian','Chef-driven'], ST_SetSRID(ST_MakePoint(29.0254, 41.0445), 4326), 'İstanbul', 'İstanbul', 'Türkiye',
 'Yeldeğirmeni, Kadıköy', 'https://images.unsplash.com/photo-1517248135467-4c7edcad34c4?w=1200', ARRAY['https://images.unsplash.com/photo-1552566626-52f8b828add9?w=1200'],
 3, '2025-01-05T10:00:00Z', '2025-06-05T10:00:00Z'),
('20000000-0000-0000-0000-000000000006', 'Limon Roof', 'Narenciye kokteylleri ve düşük tempolu canlı setlerle Çeşme gecelerine bakan teras.', 'NIGHTLIFE',
 ARRAY['Rooftop','Cocktails','Live music'], ST_SetSRID(ST_MakePoint(26.3031, 38.3235), 4326), 'Çeşme', 'İzmir', 'Türkiye',
 'Musalla Mah., Çeşme', 'https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=1200', ARRAY['https://images.unsplash.com/photo-1470229722913-7c0e2dbbafd3?w=1200'],
 3, '2025-01-06T10:00:00Z', '2025-06-06T10:00:00Z'),
('20000000-0000-0000-0000-000000000007', 'Perge Günbatımı Turu', 'Antik kentin sütunlu caddelerinde küçük grupla yapılan günbatımı yürüyüşü.', 'ATTRACTION',
 ARRAY['Ancient city','Guided walk'], ST_SetSRID(ST_MakePoint(30.8534, 36.9615), 4326), 'Aksu', 'Antalya', 'Türkiye',
 'Perge Antik Kenti, Aksu', 'https://images.unsplash.com/photo-1524230572899-a752b3835840?w=1200', ARRAY['https://images.unsplash.com/photo-1564399579883-451a5d44ec08?w=1200'],
 2, '2025-01-07T10:00:00Z', '2025-06-07T10:00:00Z'),
('20000000-0000-0000-0000-000000000008', 'Kelebek Koyu İskelesi', 'Sahil yolunun altında saklı, yüzme iskelesi ve küçük mutfağı olan rahat bir koy.', 'BEACH',
 ARRAY['Swimming deck','Casual food'], ST_SetSRID(ST_MakePoint(29.6707, 36.1845), 4326), 'Kaş', 'Antalya', 'Türkiye',
 'Çukurbağ Yarımadası, Kaş', 'https://images.unsplash.com/photo-1473116763249-2faaef81ccda?w=1200', ARRAY['https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?w=1200'],
 2, '2025-01-08T10:00:00Z', '2025-06-08T10:00:00Z'),
('20000000-0000-0000-0000-000000000009', 'Kazdağı Cam Teras Rotası', 'Köy yollarından orman patikalarına uzanan, körfez manzaralı rehberli yürüyüş.', 'NATURE',
 ARRAY['Hiking','Forest','Viewpoint'], ST_SetSRID(ST_MakePoint(26.9080, 39.6940), 4326), 'Edremit', 'Balıkesir', 'Türkiye',
 'Kazdağları Milli Parkı, Edremit', 'https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=1200', ARRAY['https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=1200'],
 1, '2025-01-09T10:00:00Z', '2025-06-09T10:00:00Z'),
('20000000-0000-0000-0000-000000000010', 'Balat Fırın No. 8', 'Ekşi mayalı ekmek, iyi filtre kahve ve mahalle masaları sunan köşe fırını.', 'CAFE',
 ARRAY['Bakery','Brunch','Coffee'], ST_SetSRID(ST_MakePoint(28.9482, 41.0294), 4326), 'İstanbul', 'İstanbul', 'Türkiye',
 'Balat Mah., Fatih', 'https://images.unsplash.com/photo-1509440159596-0249088772ff?w=1200', ARRAY['https://images.unsplash.com/photo-1511081692775-05d0f180a065?w=1200'],
 2, '2025-01-10T10:00:00Z', '2025-06-10T10:00:00Z'),
('20000000-0000-0000-0000-000000000011', 'Bozcaada Bağ Evi', 'Bağların ortasında yerel kahvaltı ve günbatımı tadımı sunan altı odalı konukevi.', 'HOTEL',
 ARRAY['Vineyard','Guesthouse'], ST_SetSRID(ST_MakePoint(26.0472, 39.8347), 4326), 'Bozcaada', 'Çanakkale', 'Türkiye',
 'Cumhuriyet Mah., Bozcaada', 'https://images.unsplash.com/photo-1542314831-068cd1dbfeeb?w=1200', ARRAY['https://images.unsplash.com/photo-1564501049412-61c2a3083791?w=1200'],
 3, '2025-01-11T10:00:00Z', '2025-06-11T10:00:00Z'),
('20000000-0000-0000-0000-000000000012', 'Fırtına Vadisi Rafting', 'Deneyimli rehberlerle Fırtına Deresi üzerinde başlangıç seviyesine uygun parkur.', 'ACTIVITY',
 ARRAY['Rafting','Outdoor','Guided'], ST_SetSRID(ST_MakePoint(40.9469, 41.1617), 4326), 'Ardeşen', 'Rize', 'Türkiye',
 'Fırtına Vadisi, Ardeşen', 'https://images.unsplash.com/photo-1530866495561-507c9faab2ed?w=1200', ARRAY['https://images.unsplash.com/photo-1519671282429-b44660ead0a7?w=1200'],
 2, '2025-01-12T10:00:00Z', '2025-06-12T10:00:00Z'),
('20000000-0000-0000-0000-000000000013', 'Karaköy Caz Mahzeni', 'Tarihi bir handa yerel caz üçlüleri ve kısa bir kokteyl menüsü.', 'BAR',
 ARRAY['Jazz','Cocktails'], ST_SetSRID(ST_MakePoint(28.9784, 41.0220), 4326), 'İstanbul', 'İstanbul', 'Türkiye',
 'Karaköy, Beyoğlu', 'https://images.unsplash.com/photo-1514933651103-005eec06c04b?w=1200', ARRAY['https://images.unsplash.com/photo-1485872299829-c673f5194813?w=1200'],
 3, '2025-01-13T10:00:00Z', '2025-06-13T10:00:00Z'),
('20000000-0000-0000-0000-000000000014', 'Göbeklitepe Sabah Rotası', 'Kalabalık gelmeden önce başlayan arkeolog anlatımlı erken saat ziyareti.', 'ATTRACTION',
 ARRAY['Archaeology','Guided tour'], ST_SetSRID(ST_MakePoint(38.9225, 37.2231), 4326), 'Şanlıurfa', 'Şanlıurfa', 'Türkiye',
 'Örencik, Haliliye', 'https://images.unsplash.com/photo-1568322445389-f64ac2515020?w=1200', ARRAY['https://images.unsplash.com/photo-1599940824399-b87987ceb72a?w=1200'],
 2, '2025-01-14T10:00:00Z', '2025-06-14T10:00:00Z'),
('20000000-0000-0000-0000-000000000015', 'Salda Sessiz Kıyı', 'Ana plajdan uzakta, göl manzarasını korumaya odaklı işaretsiz yürüyüş noktası.', 'NATURE',
 ARRAY['Lake','Walking','Quiet'], ST_SetSRID(ST_MakePoint(29.6516, 37.5509), 4326), 'Yeşilova', 'Burdur', 'Türkiye',
 'Salda Gölü, Yeşilova', 'https://images.unsplash.com/photo-1500534314209-a25ddb2bd429?w=1200', ARRAY['https://images.unsplash.com/photo-1470770841072-f978cf4d019e?w=1200'],
 1, '2025-01-15T10:00:00Z', '2025-06-15T10:00:00Z');

INSERT INTO visits (
    id, user_id, place_id, visited_at, overall_rating, public_review, private_memory,
    photos, visibility, verification_status, created_at, updated_at
) VALUES
('30000000-0000-0000-0000-000000000001', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000001', '2025-05-18', 9.2, 'Otlar ve deniz ürünleri çok dengeliydi; avlu akşamüstü harika.', 'Bir sonraki sefer erken rezervasyon yap.', ARRAY[]::TEXT[], 'PUBLIC', 'LOCATION_CONFIRMED', '2025-05-18T19:30:00Z', '2025-05-18T19:30:00Z'),
('30000000-0000-0000-0000-000000000002', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000002', '2025-05-20', 8.8, 'Çekirdek seçimi ve sakin arka bahçe çok iyi.', 'Etiyopya doğal işlenmiş çekirdeği al.', ARRAY[]::TEXT[], 'PUBLIC', 'LOCATION_CONFIRMED', '2025-05-20T11:00:00Z', '2025-05-20T11:00:00Z'),
('30000000-0000-0000-0000-000000000003', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000003', '2025-05-22', 9.5, 'Sabah erken saatte su cam gibiydi.', 'Su ve atıştırmalık getir.', ARRAY[]::TEXT[], 'FRIENDS', 'LOCATION_CONFIRMED', '2025-05-22T09:00:00Z', '2025-05-22T09:00:00Z'),
('30000000-0000-0000-0000-000000000004', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000004', '2025-04-12', 9.0, 'Konum ve kahvaltı özellikle başarılı.', 'Üst kattaki köşe oda daha sessiz.', ARRAY[]::TEXT[], 'PUBLIC', 'UNVERIFIED', '2025-04-13T08:00:00Z', '2025-04-13T08:00:00Z'),
('30000000-0000-0000-0000-000000000005', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000007', '2025-03-28', 9.3, 'Günbatımı ışığında Perge bambaşka görünüyor.', '', ARRAY[]::TEXT[], 'PUBLIC', 'LOCATION_CONFIRMED', '2025-03-28T20:00:00Z', '2025-03-28T20:00:00Z'),
('30000000-0000-0000-0000-000000000006', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000009', '2024-10-06', 9.4, 'Orman sessizliği ve körfez manzarası rotaya değer.', 'Yağmur sonrası ayakkabıya dikkat.', ARRAY[]::TEXT[], 'FRIENDS', 'LOCATION_CONFIRMED', '2024-10-06T17:00:00Z', '2024-10-06T17:00:00Z'),
('30000000-0000-0000-0000-000000000007', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000010', '2025-02-16', 8.7, 'Ekşi maya ve filtre kahve için güvenilir mahalle adresi.', '', ARRAY[]::TEXT[], 'PUBLIC', 'UNVERIFIED', '2025-02-16T13:00:00Z', '2025-02-16T13:00:00Z'),
('30000000-0000-0000-0000-000000000008', '11111111-1111-1111-1111-111111111111', '20000000-0000-0000-0000-000000000014', '2024-11-09', 9.6, 'Sabah sessizliğinde anlatım çok daha etkileyiciydi.', 'Müze kartını önceden hazırla.', ARRAY[]::TEXT[], 'PUBLIC', 'LOCATION_CONFIRMED', '2024-11-09T12:00:00Z', '2024-11-09T12:00:00Z');

INSERT INTO visit_dimension_scores (visit_id, dimension_key, score) VALUES
('30000000-0000-0000-0000-000000000001','FOOD',9.4), ('30000000-0000-0000-0000-000000000001','SERVICE',8.9), ('30000000-0000-0000-0000-000000000001','ATMOSPHERE',9.5), ('30000000-0000-0000-0000-000000000001','VALUE',8.4), ('30000000-0000-0000-0000-000000000001','PRESENTATION',9.3),
('30000000-0000-0000-0000-000000000002','FOOD',8.7), ('30000000-0000-0000-0000-000000000002','SERVICE',8.8), ('30000000-0000-0000-0000-000000000002','ATMOSPHERE',9.1), ('30000000-0000-0000-0000-000000000002','VALUE',8.5), ('30000000-0000-0000-0000-000000000002','PRESENTATION',8.9),
('30000000-0000-0000-0000-000000000003','SEA',9.8), ('30000000-0000-0000-0000-000000000003','ATMOSPHERE',9.7), ('30000000-0000-0000-0000-000000000003','SERVICE',7.5), ('30000000-0000-0000-0000-000000000003','CLEANLINESS',9.3), ('30000000-0000-0000-0000-000000000003','VALUE',9.0), ('30000000-0000-0000-0000-000000000003','CROWD',9.6),
('30000000-0000-0000-0000-000000000004','CLEANLINESS',9.2), ('30000000-0000-0000-0000-000000000004','LOCATION',9.7), ('30000000-0000-0000-0000-000000000004','ROOM',8.6), ('30000000-0000-0000-0000-000000000004','SERVICE',9.1), ('30000000-0000-0000-0000-000000000004','BREAKFAST',9.3), ('30000000-0000-0000-0000-000000000004','VALUE',8.2),
('30000000-0000-0000-0000-000000000005','EXPERIENCE',9.6), ('30000000-0000-0000-0000-000000000005','ACCESS',8.5), ('30000000-0000-0000-0000-000000000005','ATMOSPHERE',9.7), ('30000000-0000-0000-0000-000000000005','VALUE',9.0),
('30000000-0000-0000-0000-000000000006','SCENERY',9.8), ('30000000-0000-0000-0000-000000000006','ACCESS',8.2), ('30000000-0000-0000-0000-000000000006','CLEANLINESS',9.4), ('30000000-0000-0000-0000-000000000006','TRANQUILITY',9.7),
('30000000-0000-0000-0000-000000000007','FOOD',8.8), ('30000000-0000-0000-0000-000000000007','SERVICE',8.5), ('30000000-0000-0000-0000-000000000007','ATMOSPHERE',8.7), ('30000000-0000-0000-0000-000000000007','VALUE',8.9), ('30000000-0000-0000-0000-000000000007','PRESENTATION',8.6),
('30000000-0000-0000-0000-000000000008','EXPERIENCE',9.9), ('30000000-0000-0000-0000-000000000008','ACCESS',8.8), ('30000000-0000-0000-0000-000000000008','ATMOSPHERE',9.8), ('30000000-0000-0000-0000-000000000008','VALUE',9.3);

INSERT INTO saved_places (user_id, place_id, saved_at) VALUES
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000003','2025-01-20T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000005','2025-02-03T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000008','2025-02-15T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000009','2025-03-01T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000011','2025-03-10T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000012','2025-04-01T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000014','2025-04-20T10:00:00Z'),
('11111111-1111-1111-1111-111111111111','20000000-0000-0000-0000-000000000015','2025-05-01T10:00:00Z');

INSERT INTO collections (id, user_id, title, description, visibility, cover_image, created_at, updated_at) VALUES
('40000000-0000-0000-0000-000000000001','11111111-1111-1111-1111-111111111111','Ege''de Uzun Hafta Sonu','Koylar, iyi sofralar ve küçük oteller.','PUBLIC','https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=1200','2025-01-10T10:00:00Z','2025-05-25T10:00:00Z'),
('40000000-0000-0000-0000-000000000002','11111111-1111-1111-1111-111111111111','İstanbul Kahve ve Gece','Kahvaltıdan canlı müziğe şehir rotası.','FRIENDS','https://images.unsplash.com/photo-1524231757912-21f4fe3a7200?w=1200','2025-02-10T10:00:00Z','2025-05-26T10:00:00Z'),
('40000000-0000-0000-0000-000000000003','11111111-1111-1111-1111-111111111111','Sıradaki Doğa Kaçamakları','Henüz gitmediğim yürüyüş ve açık hava rotaları.','PRIVATE','https://images.unsplash.com/photo-1441974231531-c6227db76b6e?w=1200','2025-03-10T10:00:00Z','2025-05-27T10:00:00Z');

INSERT INTO collection_places (collection_id, place_id, display_order, added_at) VALUES
('40000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001',0,'2025-01-10T10:00:00Z'),
('40000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000003',1,'2025-01-10T10:01:00Z'),
('40000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000004',2,'2025-01-10T10:02:00Z'),
('40000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000011',3,'2025-01-10T10:03:00Z'),
('40000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000005',0,'2025-02-10T10:00:00Z'),
('40000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000010',1,'2025-02-10T10:01:00Z'),
('40000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000013',2,'2025-02-10T10:02:00Z'),
('40000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000009',0,'2025-03-10T10:00:00Z'),
('40000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000012',1,'2025-03-10T10:01:00Z'),
('40000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000015',2,'2025-03-10T10:02:00Z');
