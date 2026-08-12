package com.superprogrammer.asset.service;

import com.superprogrammer.asset.mapper.AssetMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetFileAccessGrantorTest {

    @Mock
    private AssetMapper assetMapper;

    @Test
    void blankArgs_denyWithoutQuery() {
        AssetFileAccessGrantor grantor = new AssetFileAccessGrantor(assetMapper);

        assertFalse(grantor.canAccess(null, 4L));
        assertFalse(grantor.canAccess("  ", 4L));
        assertFalse(grantor.canAccess("asset.jpg", null));
        verifyNoInteractions(assetMapper);
    }

    @Test
    void accessibleAssetReference_allowsFile() {
        when(assetMapper.countAccessibleFileReferences("asset.jpg", 4L)).thenReturn(1L);

        assertTrue(new AssetFileAccessGrantor(assetMapper).canAccess("asset.jpg", 4L));
    }

    @Test
    void noAccessibleAssetReference_deniesFile() {
        when(assetMapper.countAccessibleFileReferences("asset.jpg", 5L)).thenReturn(0L);

        assertFalse(new AssetFileAccessGrantor(assetMapper).canAccess("asset.jpg", 5L));
    }
}
