package vn.careermap.web.dto;

import vn.careermap.domain.TileType;

public record TileResponse(int position, TileType type, String question) {}