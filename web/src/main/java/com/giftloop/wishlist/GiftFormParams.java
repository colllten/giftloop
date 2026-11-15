package com.giftloop.wishlist;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;

/**
 * Raw fields of the add/edit gift form (multipart, so a photo can ride along). Bound with
 * {@code @BeanParam}; public fields because RESTEasy Reactive injects them directly.
 */
public class GiftFormParams {

    /** Gift name. */
    @RestForm
    public String name;
    /** Optional price. */
    @RestForm
    public String price;
    /** How many. */
    @RestForm
    public String quantity;
    /** Links, one per line. */
    @RestForm
    public String links;
    /** Optional notes. */
    @RestForm
    public String notes;
    /** {@code everyone} or {@code selected}. */
    @RestForm
    public String visibility;
    /** Selected member ids. */
    @RestForm
    public List<String> viewers;
    /** Staged base64 JPEG from auto-fill or an earlier upload. */
    @RestForm("image_data")
    public String imageData;
    /** "Remove photo" checkbox. */
    @RestForm("remove_image")
    public String removeImage;
    /** Link pasted for auto-fill (ignored on save). */
    @RestForm("source_url")
    public String sourceUrl;
    /** Newly chosen photo file, if any. */
    @RestForm
    public FileUpload photo;

    /**
     * Normalized form values (the photo file is handled separately).
     *
     * @return form
     */
    public GiftForm toForm() {
        return GiftForm.of(name, price, quantity, links, notes, visibility, viewers)
                .withPhoto(imageData, "true".equals(removeImage));
    }

    /**
     * Whether a photo file was chosen.
     *
     * @return true if a non-empty file was uploaded
     */
    public boolean hasPhoto() {
        return photo != null && photo.size() > 0 && photo.fileName() != null && !photo.fileName().isBlank();
    }
}
