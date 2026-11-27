"""Walk the built sector with the engine's deterministic test player."""
PLAY = dict(seconds=90)


def test_crossroads_has_standing_and_movement(play):
    session = play('royale_crossroads')
    places = session.places()
    assert len(places) == 90 and session.deaths(0) == 0
    assert max(x for x,y,z in places) - min(x for x,y,z in places) > 1
    assert min(z for x,y,z in places) >= -0.01
