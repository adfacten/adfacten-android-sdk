package com.adfacten.ads.internal

internal object Fixtures {
    fun adJson(
        format: String = "banner",
        kind: String = "image",
        expiresAt: Long = 1_794_160_000L,
        extra: String = "",
        base: String = "https://trk.adfacten.com",
    ) = """
        {"ad":{
          "id":"imp_tok_1","format":"$format","kind":"$kind","w":320,"h":50,
          "image":{"url":"https://cdn.adfacten.com/a.webp","url_2x":"https://cdn.adfacten.com/a@2x.webp","fallback":"https://cdn.adfacten.com/a.jpg"},
          "html":"<!doctype html><p>hi</p>",
          "vast":"<VAST version=\"4.1\"></VAST>",
          "native":{"title":"Title","body":"Body","brand":"Brand","cta":"Install","image":{"url":"https://cdn.adfacten.com/n.jpg","w":1200,"h":628},"icon":{"url":"https://cdn.adfacten.com/i.png"}},
          "click_url":"$base/a/click?imp=imp_tok_1",
          "trackers":{"render":"$base/i?imp=imp_tok_1&e=render","view":"$base/i?imp=imp_tok_1&e=view",
                      "complete":"$base/i?imp=imp_tok_1&e=complete","close":"$base/i?imp=imp_tok_1&e=close"},
          "view_rule":{"min_ratio":0.5,"min_ms":1000},
          "close_after_s":5,
          "reward":{"type":"coins","amount":10},
          "label":"Reklam",
          "expires_at":$expiresAt
          $extra
        }}
    """.trimIndent()

    val VAST = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="4.1">
          <Ad id="vad1">
            <InLine>
              <AdSystem>Adfacten</AdSystem>
              <AdTitle>Spot</AdTitle>
              <Impression><![CDATA[https://trk.example/imp?cb=[CACHEBUSTING]]]></Impression>
              <Impression>https://trk.example/imp2</Impression>
              <Error><![CDATA[https://trk.example/err?code=[ERRORCODE]]]></Error>
              <Creatives>
                <Creative id="c1">
                  <Linear skipoffset="00:00:05">
                    <Duration>00:00:30.500</Duration>
                    <TrackingEvents>
                      <Tracking event="start">https://trk.example/start</Tracking>
                      <Tracking event="firstQuartile">https://trk.example/q1</Tracking>
                      <Tracking event="midpoint">https://trk.example/mid</Tracking>
                      <Tracking event="thirdQuartile">https://trk.example/q3</Tracking>
                      <Tracking event="complete">https://trk.example/complete</Tracking>
                      <Tracking event="complete">https://trk2.example/complete</Tracking>
                      <Tracking event="mute">https://trk.example/mute</Tracking>
                    </TrackingEvents>
                    <VideoClicks>
                      <ClickThrough><![CDATA[https://advertiser.example/landing]]></ClickThrough>
                      <ClickTracking>https://trk.example/click</ClickTracking>
                    </VideoClicks>
                    <MediaFiles>
                      <MediaFile delivery="progressive" type="video/mp4" width="1920" height="1080" bitrate="6000"><![CDATA[https://cdn.example/1080.mp4]]></MediaFile>
                      <MediaFile delivery="progressive" type="video/mp4" width="1280" height="720" bitrate="2500"><![CDATA[https://cdn.example/720.mp4]]></MediaFile>
                      <MediaFile delivery="progressive" type="video/mp4" width="640" height="360" bitrate="800"><![CDATA[https://cdn.example/360.mp4]]></MediaFile>
                      <MediaFile delivery="streaming" type="application/x-mpegURL" width="1280" height="720"><![CDATA[https://cdn.example/master.m3u8]]></MediaFile>
                      <MediaFile delivery="progressive" type="video/webm" width="1280" height="720" bitrate="2000"><![CDATA[https://cdn.example/720.webm]]></MediaFile>
                    </MediaFiles>
                  </Linear>
                </Creative>
              </Creatives>
            </InLine>
          </Ad>
        </VAST>
    """.trimIndent()
}
