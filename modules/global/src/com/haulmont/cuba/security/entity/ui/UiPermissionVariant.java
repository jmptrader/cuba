/*
 * Copyright (c) 2011 Haulmont Technology Ltd. All Rights Reserved.
 * Haulmont Technology proprietary and confidential.
 * Use is subject to license terms.
 */

package com.haulmont.cuba.security.entity.ui;

import com.haulmont.chile.core.datatypes.impl.EnumClass;
import org.apache.commons.lang.ObjectUtils;

/**
 * <p>$Id$</p>
 *
 * @author artamonov
 */
public enum UiPermissionVariant implements EnumClass<Integer> {

    /**
     * Read-only
     */
    READ_ONLY(10, "blue"),

    /**
     * Hide
     */
    HIDE(20, "red"),

    /**
     * Permission not selected
     */
    NOTSET(30, "black");

    private Integer id;

    private String color;

    UiPermissionVariant(Integer id, String color) {
        this.id = id;
        this.color = color;
    }

    @Override
    public Integer getId() {
        return id;
    }

    public String getColor() {
        return color;
    }

    public static UiPermissionVariant fromId(Integer id) {
        for (UiPermissionVariant variant : UiPermissionVariant.values()) {
            if (ObjectUtils.equals(variant.getId(), id)) {
                return variant;
            }
        }
        return null;
    }
}
