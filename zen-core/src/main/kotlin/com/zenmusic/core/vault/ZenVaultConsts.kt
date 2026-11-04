package com.zenmusic.core.vault

/**
 * GENERATED FILE — do not edit by hand.
 *
 * Built-in secrets sealed with AES-256-GCM. Regenerate with
 * `scripts/gen_zenvault.py` + `scripts/gen_zenvault_extra.py`
 * (also rotates the key fragments in ZenVaultKey).
 *
 * Semantic ids (what each ciphertext decrypts to) are documented in the
 * project README — the plaintext values never appear in source or bytecode.
 */
internal object ZenVaultConsts {
    val SECRETS: Map<String, String> = mapOf(
        "it.host" to "nlig8/sN2FWKV7kNqGh52U0MedZs75/KwJfn4cWIz2/noicoGQT+akserp06",
        "it.key.remix" to "va4paeGVJ18PTDV8fSTMwLohllIghT+ho1L3mYDyBfXIk/fW1Y6aY31u9QefkK7CuaeG2IPFTPMjz+LOeljwSYB1fg==",
        "it.ver.remix" to "YmHiD4kiwzIGq4KmMermFTjnpePJN5Bw5SUB7qcBFKcsVxfGTdu4deEfQcs=",
        "it.origin" to "86fcgcm3WqlLHW+nYr00orAUdEKMIOxfrXb8NpgI5sT4wFKEB/kSffOGzNtYih5hkTcwnXQ=",
        "it.ver.visionos" to "pLK4k3PTm244mqvKiwwyFY+daNYQGyb/i9Cdd9V9nYo=",
        "ua.visionos" to "YO/Bb3/ruxpPZhopbPFMCsGuEoCIVmOGpnv/UXGeKHxoiESiUAttTt95UUWSvVuP7VmkVXdJeSkUg17eLfVSygKxSIHKntzZWPOkfJeWzOmxYpcQ9j6pDDqYz/DiMXC9lVtwx7b8xExMgiFS4625jX4c49LnZKnbEyPcpZqnFQjmOkZJU2bTxjviDdmTnHya",
        "it.ver.visionos2" to "8JcRUTWcYCzd1LWLpXSybGlia2DJjrdVN0ENHo3HlnM=",
        "it.ver.tvs" to "rDGayRpkMXVBeAmv0f0eL113NJC5DQdzxm9Mf4jnnA==",
        "ua.tvs" to "fCh/FADMFcDin6IayjKnhcXhtpj3Bl5C1iiFhSQUjRibQA0ZZzCIXt/9leccUc5iIaACc5sbrbF5V0QKoqgaMZMXVPnHxYWbuvGs9RUUqdgGqWn34/Dk8nttoOma+t667VU6T9iU6pWDM1ZdBWxIunpIj42FXNAzoPzMhzHYUCqT7lr+eoT/ykqx9nbpo9hpGNIh3n2KxOkN69sFxQ==",
        "ua.web" to "1yxHu38ttxisorEufo1tcJyxydrPLBye2NuJILqO2hzrSOUTdiySZdm9p3JQS+x8hQEV25kRmT0m+p5oHXm91WvqBV49Mamjaab+9vtQwnClwjfLBW66mjvf+TxwPZA8RKde8JJonPFo2wsT",
        "ua.zen" to "PKaHbP8SCCXBmXF3pHFuzJMUyqe20GV/XQ/YdvScGlYKAcSBrpT6PujMWt6UtJA86wh8cRHV/laLM3i5EQ==",
        "gv.host" to "kBJuGnbvvLKYRoxMpaBdfyaoXVpB9BgJSfyDBuRMog5nx5QpCnv5B9d/RA==",
        "jnn.host" to "Dn99UZpG0xbte+M9P18AHFnrPztgKvDA8IaRQKmpOrmjvcBjNWJIlghts8S3u/gBNQ==",
        "jnn.key" to "mBR8nBGHYjQC6kCF56asBuXwXzKpv/wk/0i498Htzu1qiSh82UnNQkeI1zWfEENGg6gs+NLCGZ/EVq0SrST+vgVi+w==",
        "jnn.reqkey" to "0qSwP/3QVXhVng0fYEl2B/UAXFHc/In5FQQyUQPyKtd1VeNdeTpYcVLnCc0ybvRV",
        "lrc.host" to "NtclLfHkI2g/RH7XP22pun02NcKOj+jhfV4Ej9UXBywrnGvQOiE=",
        "decoy.suffix" to "PeRVJPCgA/9C6TnLkv+maNA3RsuGmCaeAdmTjUHb7MFEf8WUbfiKzw==",
        "it.filter.songs" to "Ny6UNvygQdcbZ1vmZzkWLEAgCVIcHPL1nzhwy+SAtGwvl1bJHE4DFdQWyPAujgaEaxI1uu7iLk4=",
        "it.filter.videos" to "cFjEZvGXUYkpzAk7gYWtMU97Z7X9oX9vhQVIzonUe2TAIdgcEy3DD8akDaR6ttV3+N4VA1Xey7g=",
        "it.filter.albums" to "UzY6h2UGj2HBrA3RDGJOeaBcylLICgQ0BG7+rCQ+OnZ6ZilD38RDJhHtjZ02DVeHLmt5qISCFoY=",
        "it.filter.artists" to "YEi5iY3gjtCKNDtd5KGEL1C5YXRnF/1FCox7K8KBeAb7GjMx2+36Ggl1PGJymIiAdKe772pLWTU=",
        "web.clientver" to "HniK14l16EdVORsUt5W2Sbn7UtD794eTrtdv+SS6qjNsE8i1tyxAcgf0On4=",
        "jnn.create.path" to "zc4g6AdiA7nt3aFLtCAg19a1ifgb3MsL3pmR1jhhmMPol1I8ouBCaPgoR6rEEv1keOp32gi5DM7LDxPIbNCdtaibDg==",
        "jnn.generate.path" to "GE14UE54wJxeIdlhY5kJFENE2SaZQMT4vN/ZaCewFRLTHHDuTooSdKMwBD7ieZeRvRQNRzpVKX6RvV2bS0G/vnCO4morCWc=",
        "status.ok" to "Mx1lKJxWSBlWpbbZcyqGBOiOTc91xNKdEFvAWx/w",
        "img.host" to "SmbLPHOBPfGNdgNH2sCyF3VC+B8XsgYnHug3WLuXXj4CAqWr/K6E",
        "it.path.prefix" to "yLnRZyjvalLSvqcGARx7KmmoX8AnUmkyTrLIH5qPG0uLr+wjqB18gB4=",
        "hdr.client.name" to "NxV+959g78+zr+l+ghrlXY5aAsxwp56FmZVT0Ke7ijxVngl15IxlCWkZDxTJqnpOcg==",
        "hdr.client.ver" to "U8FY4cE41RMJgKIb7XPLpx6OS2OEMB087zOuw0Kq/oIPb8IJjeGe6e9DYdDf/U3FPdvphQ==",
        "yt.host" to "MoKZVfRo2zG90dSNO0f3ka/kuuIPObWfIQFnyVca+V1ji3RVcmuYM+Jugg==",
        "yt.origin" to "Ai382J7O5iOfKD+BSttpnanm0r36pprigFjHAmHwsHCkrctqHHEFvGVPJkG7VwpswCPp",
        "yt.filter.videos" to "FAieGbTn9r0OHShKMBwuMeDesoBrf0Vv2gPUs2BqRKa40ffibQHhiw==",
    )
}
